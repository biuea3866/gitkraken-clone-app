package dev.undine.infrastructure.git.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.AmendPreflight
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.DiffHunk
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.ref.baselineHeld
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.UserConfig
import java.io.File

private const val OPERATION_STAGE = "staging.stage"
private const val OPERATION_UNSTAGE = "staging.unstage"
private const val OPERATION_STAGE_HUNKS = "staging.stageHunks"
private const val OPERATION_COMMIT = "staging.commit"
private const val OPERATION_INSPECT_AMEND = "staging.inspectAmend"
private const val OPERATION_AMEND = "staging.amend"
private const val EMPTY_MESSAGE_DETAIL = "커밋 메시지가 비어 있습니다"

/**
 * JGit 실패를 도메인 실패로 번역한다.
 *
 * [UndineException] 은 이미 번역된 것이므로 그대로 올리고, [CancellationException] 은
 * 삼키면 코루틴 취소가 동작하지 않으므로 반드시 전파한다.
 * 나머지는 사용자가 고칠 수 없는 실패이므로 **최후 수단**인
 * [UndineException.GitOperationFailed] 로 감싸 원인을 보존한다.
 */
@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
internal inline fun <T> translateGitFailure(operation: String, block: () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (translated: UndineException) {
        throw translated
    } catch (failure: Exception) {
        throw UndineException.GitOperationFailed(operation, failure)
    }

/**
 * [StagingGateway] 의 JGit 구현.
 *
 * 모든 Git 접근은 [GitAccess] 를 거친다 — JGit `Repository` 는 스레드 안전하지 않으므로
 * 다른 Gateway 구현과 **같은 직렬 경계**를 공유해야 한다. 직렬화도 `Dispatchers.IO` 실행도
 * [GitAccess] 책임이라 이 클래스는 락이나 `withContext` 를 다시 걸지 않고, 핸들도 닫지 않는다
 * (수명은 저장소 홀더 소유다). 배선(GitAccess → 각 Gateway 구현)은 UND-26 이 한다.
 *
 * 커밋을 되돌리는 API 는 여기에 없다 — soft reset 은 `WorktreeOpsGateway.reset` 소관이다.
 *
 * amend 는 HEAD 를 다시 쓰는 파괴적 연산이라 방어선을 둘 둔다.
 * 앞선 것은 **사전 확인**이다 — [inspectAmend] 로 대상과 원격 포함 여부를 알린 뒤,
 * [amend] 가 실행 직전 같은 값을 다시 읽어 허가가 여전히 유효한지 검사한다.
 * 뒤선 것은 결정문 C2(force push)와 같은 방식의 **백업 ref** 다 — 확인을 거친 amend 라도
 * 고치기 직전 원본 커밋을 남겨 되돌릴 지점을 만든다. 백업에 실패하면 amend 를 진행하지 않는다.
 *
 * @param currentTimeMillis 백업 ref 이름에 쓰는 시각. 테스트가 고정값을 주입한다.
 */
class StagingGatewayImpl(
    private val gitAccess: GitAccess,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : StagingGateway {

    override suspend fun stage(paths: List<String>) {
        if (paths.isEmpty()) return
        gitAccess.withRepository { repository ->
            translateGitFailure(OPERATION_STAGE) { repository.stagePaths(paths) }
        }
    }

    override suspend fun unstage(paths: List<String>) {
        if (paths.isEmpty()) return
        gitAccess.withRepository { repository ->
            translateGitFailure(OPERATION_UNSTAGE) { repository.unstagePaths(paths) }
        }
    }

    override suspend fun stageHunks(path: String, hunks: List<DiffHunk>) {
        if (hunks.isEmpty()) return
        gitAccess.withRepository { repository ->
            translateGitFailure(OPERATION_STAGE_HUNKS) { repository.stageHunksOf(path, hunks) }
        }
    }

    override suspend fun commit(message: String): CommitResult {
        if (message.isBlank()) throw UndineException.StateViolation(EMPTY_MESSAGE_DETAIL)
        return gitAccess.withRepository { repository ->
            repository.requireAuthorConfigured()
            translateGitFailure(OPERATION_COMMIT) { repository.createCommit(message) }
        }
    }

    override suspend fun inspectAmend(): AmendPreflight = gitAccess.withRepository { repository ->
        translateGitFailure(OPERATION_INSPECT_AMEND) { repository.inspectAmendTarget() }
    }

    override suspend fun amend(message: String, confirmation: AmendConfirmation): CommitResult {
        if (message.isBlank()) throw UndineException.StateViolation(EMPTY_MESSAGE_DETAIL)
        return gitAccess.withRepository { repository ->
            repository.requireAuthorConfigured()
            translateGitFailure(OPERATION_AMEND) {
                repository.amendCommit(message, confirmation, currentTimeMillis)
            }
        }
    }
}

/**
 * 추가 경로와 삭제 경로를 **함께** 인덱스에 반영한다.
 *
 * `add` 와 `rm --cached` 는 각자 DirCache 를 잠그는 별개의 단계라 뒤 단계가 실패하면 앞 단계만
 * 남는다 — 사용자가 고르지 않은 부분 스테이징 상태다. 그래서 실패 시 원래 인덱스로 되돌린다.
 */
private fun Repository.stagePaths(paths: List<String>) {
    val (removed, present) = paths.partition { path -> !File(workTree, path).exists() }
    withIndexRestoredOnFailure {
        Git(this).use { git ->
            if (present.isNotEmpty()) {
                val add = git.add()
                present.forEach { path -> add.addFilepattern(path) }
                add.call()
            }
            // AddCommand 는 사라진 파일을 인덱스에서 지우지 않는다 — 삭제는 rm --cached 로 기록한다.
            if (removed.isNotEmpty()) git.removeFromIndex(removed)
        }
    }
}

private fun Repository.unstagePaths(paths: List<String>) {
    Git(this).use { git ->
        // 커밋이 없는 저장소에는 되돌릴 HEAD 가 없다 — 인덱스에서 빼는 것이 unstage 다.
        if (resolve(Constants.HEAD) == null) {
            git.removeFromIndex(paths)
        } else {
            val reset = git.reset().setRef(Constants.HEAD)
            paths.forEach { path -> reset.addPath(path) }
            reset.call()
        }
    }
}

private fun Repository.stageHunksOf(path: String, hunks: List<DiffHunk>) {
    val entry = readDirCache().getEntry(path)
    val base = entry?.let { readBlob(it.objectId) } ?: ""
    val patched = HunkPatchApplier.apply(base, hunks)
    writeIndexEntry(path, patched.toByteArray(), entry?.fileMode ?: FileMode.REGULAR_FILE)
}

/**
 * 커밋 **직전** HEAD 를 같은 임계 구역에서 읽어 결과에 싣는다 — 되돌리기(soft reset)의 목적지이며,
 * 호출자가 커밋 뒤에 읽으면 이미 새 커밋을 가리켜 되돌릴 지점을 잃는다 (UND-73).
 */
private fun Repository.createCommit(message: String): CommitResult =
    Git(this).use { git ->
        git.requireStagedChanges()
        val previousHead = resolve(Constants.HEAD)?.let { CommitId.of(it.name) }
        val created = git.commit().setMessage(message).call()
        CommitResult(
            commitId = CommitId.of(created.name),
            previousHead = previousHead,
            baseline = baselineHeld(),
        )
    }

private fun Git.removeFromIndex(paths: List<String>) {
    val remove = rm().setCached(true)
    paths.forEach { path -> remove.addFilepattern(path) }
    remove.call()
}

/**
 * 작성자 정보는 저장소·전역 Git 설정에서만 읽는다. 앱이 임의로 채우면
 * 되돌리기 비싼 커밋이 잘못된 이름으로 쌓인다. JGit 이 OS 사용자명으로 추측한 값
 * (`isAuthorNameImplicit`)도 미설정으로 본다.
 */
private fun Repository.requireAuthorConfigured() {
    val user = config.get(UserConfig.KEY)
    val configured = !user.isAuthorNameImplicit && !user.isAuthorEmailImplicit &&
        !user.authorName.isNullOrBlank() && !user.authorEmail.isNullOrBlank()
    if (!configured) throw UndineException.AuthorNotConfigured()
}

private fun Git.requireStagedChanges() {
    val status = status().call()
    val staged = status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()
    if (!staged) throw UndineException.NothingToCommit()
}
