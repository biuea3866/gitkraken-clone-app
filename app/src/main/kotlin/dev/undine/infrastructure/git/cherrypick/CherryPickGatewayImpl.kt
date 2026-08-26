package dev.undine.infrastructure.git.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickAbortConfirmation
import dev.undine.domain.cherrypick.CherryPickGateway
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.toOpenedRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException
import org.eclipse.jgit.api.CherryPickResult as JGitCherryPickResult

private const val OPERATION_ORDER = "cherrypick.orderOldestFirst"
internal const val OPERATION_APPLY = "cherrypick.apply"
private const val OPERATION_STATE = "cherrypick.repositoryState"
private const val OPERATION_STOPPED = "cherrypick.stoppedAt"
private const val OPERATION_CONTINUE = "cherrypick.continueAfterResolve"
private const val OPERATION_ABORT = "cherrypick.abort"

private const val NOT_IN_PROGRESS = "cherry-pick 이 진행 중이 아닙니다"
private const val START_POINT_MISSING = "되돌릴 시작 지점(ORIG_HEAD)이 없습니다"
private const val UNRESOLVED_REMAIN = "해결하지 않은 충돌이 남아 있습니다"

/** `git cherry-pick -x` 가 남기는 줄. 원본 커밋을 추적하는 유일한 단서다. */
internal const val ORIGIN_NOTE_PREFIX = "(cherry picked from commit "

/**
 * [CherryPickGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 직접 여는 자원([Git]·[RevWalk])만
 * `use {}` 로 닫는다.
 *
 * **충돌은 실패가 아니다.** [CherryPickStep.Conflicted] 로 돌려주고 저장소를 진행 중으로 남긴다.
 * 적용할 변경이 없는 경우도 실패가 아니라 [CherryPickStep.Empty] 다.
 */
class CherryPickGatewayImpl(private val gitAccess: GitAccess) : CherryPickGateway {

    override suspend fun repositoryState(): RepositoryState =
        gitOperation(OPERATION_STATE) { git -> git.repository.toOpenedRepository().state }

    /**
     * **위상 순서**로 정렬한다 — 타임스탬프로 정렬하면 같은 초에 만들어진 커밋의 순서가 뒤집힌다
     * (실측으로 그렇게 깨졌다). 조상이 먼저 오는 것이 여기서 필요한 성질이므로 커밋 그래프를 따른다.
     */
    override suspend fun orderOldestFirst(commits: List<CommitId>): List<CommitId> =
        gitOperation(OPERATION_ORDER) { git ->
            if (commits.isEmpty()) return@gitOperation emptyList()
            val requested = commits.map { commit -> git.repository.requireObject(commit) }.toSet()
            RevWalk(git.repository).use { walk ->
                walk.sort(RevSort.TOPO)
                walk.sort(RevSort.REVERSE, true)
                requested.forEach { id -> walk.markStart(walk.parseCommit(id)) }
                walk.filter { revision -> revision.id in requested }
                    .map { revision -> CommitId.of(revision.name) }
            }
        }

    /**
     * 원본 기록은 **적용 후 메시지를 고쳐** 남긴다. JGit `CherryPickCommand` 에는 `-x` 에 해당하는
     * 옵션이 없어, 만들어진 커밋의 메시지 끝에 한 줄을 붙이는 방식으로 같은 결과를 만든다.
     */
    override suspend fun apply(commit: CommitId, recordOrigin: Boolean): CherryPickStep =
        gitOperation(OPERATION_APPLY) { git -> git.applyHeld(commit, recordOrigin) }

    override suspend fun stoppedAt(): CommitId? =
        gitOperation(OPERATION_STOPPED) { git ->
            git.repository.cherryPickHeadCommit()
        }

    /**
     * 해결한 인덱스로 멈춘 커밋을 만든다.
     *
     * 미해결 파일이 남아 있으면 커밋하지 않는다 — 반쯤 해결된 상태를 커밋하면 되돌리기 어렵다.
     */
    override suspend fun continueAfterResolve(): CherryPickStep =
        gitOperation(OPERATION_CONTINUE) { git ->
            git.repository.requireCherryPicking()
            val unresolved = git.conflictedPaths()
            if (unresolved.isNotEmpty()) return@gitOperation CherryPickStep.Conflicted(unresolved)
            val stopped = git.repository.cherryPickHeadCommit()
                ?: throw UndineException.StateViolation(UNRESOLVED_REMAIN)
            val message = git.repository.messageOf(stopped)
            val created = git.commit().setMessage(message).call()
            git.repository.clearCherryPickHead()
            CherryPickStep.Created(CommitId.of(created.name))
        }

    override suspend fun abort(confirmation: CherryPickAbortConfirmation) =
        gitOperation(OPERATION_ABORT) { git ->
            git.repository.requireCherryPicking()
            // JGit 의 readOrigHead() 를 그대로 쓴다 — 같은 이름의 확장을 두면 멤버에 가려진다.
            val startPoint: ObjectId = git.repository.readOrigHead()
                ?: throw UndineException.StateViolation(START_POINT_MISSING)
            // 확인 대조는 CherryPickService 가 이미 했다 — 여기서는 실행만 한다.
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(startPoint.name).call()
            git.repository.clearCherryPickHead()
        }

    private suspend fun <T> gitOperation(operation: String, block: (Git) -> T): T =
        try {
            gitAccess.withRepository { repository ->
                // Git.wrap 은 공유 Repository 를 닫지 않는다 — 닫는 것은 Git 자신의 자원뿐이다.
                Git.wrap(repository).use(block)
            }
        } catch (failure: GitAPIException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: JGitInternalException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

internal fun Git.conflictedPaths(): List<String> = status().call().conflicting.sorted()

internal fun Repository.messageOf(commit: CommitId): String =
    RevWalk(this).use { walk -> walk.parseCommit(requireObject(commit)).fullMessage }

/**
 * 커밋 존재까지 확인한다.
 *
 * `resolve` 는 완전한 40자 hex 를 **존재 여부와 무관하게** 그대로 ObjectId 로 만들어 준다 — 그것만
 * 믿으면 없는 커밋이 한참 뒤 JGit 내부 오류로 터져 "찾을 수 없다" 가 "알 수 없는 실패" 로 보인다.
 */
internal fun Repository.requireObject(commit: CommitId): ObjectId {
    val id = resolve(commit.value)
    if (id == null || !objectDatabase.has(id)) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return id
}

private fun Repository.requireCherryPicking() {
    if (toOpenedRepository().state != RepositoryState.CHERRY_PICKING) {
        throw UndineException.StateViolation(NOT_IN_PROGRESS)
    }
}

/** 이름을 JGit 의 `readCherryPickHead()` 와 다르게 둔다 — 같으면 확장이 자기를 부른다. */
private fun Repository.cherryPickHeadCommit(): CommitId? =
    readCherryPickHead()?.let { head -> CommitId.of(head.name) }

private fun Repository.clearCherryPickHead() {
    writeCherryPickHead(null)
    writeMergeCommitMsg(null)
}
