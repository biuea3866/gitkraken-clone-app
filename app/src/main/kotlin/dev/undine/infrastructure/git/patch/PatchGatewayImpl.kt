package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import dev.undine.domain.patch.ApplyMode
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitRange
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.PatchGateway
import dev.undine.domain.patch.UnsupportedReason
import dev.undine.domain.patch.WorkingTreeScope
import dev.undine.infrastructure.git.repository.GitAccess
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader

/**
 * [PatchGateway] 의 JGit 구현. **외부 `git` 바이너리를 부르지 않는다** — 미설치 환경 동작이나
 * 임시 파일 정리 정책 같은 문제를 애초에 만들지 않기 위해 외부 프로세스 경로 자체를 두지 않는다.
 *
 * 적용의 뼈대는 **트리 트랜잭션**이다:
 * 1. 경로 안전 판정 → 복원 불가 상태 확인 (둘 다 실패하면 워킹트리를 건드리기 전에 거부)
 * 2. 적용 전 추적 상태를 트리로 기록 ([recordPatchTree])
 * 3. 그 트리 **위에서만** 적용해 본다 ([applyIsolated]) — 워킹트리는 아직 그대로다
 * 4. 통과한 결과만 승격하고, 승격·검증이 실패하면 기록한 트리로 되돌린다
 *
 * `dryRun` 은 3까지만 하고 4를 건너뛴다. 두 진입점이 **같은 경로**를 쓰므로
 * "검사는 통과했는데 적용은 실패" 가 생길 수 없다.
 *
 * JGit `Repository` 는 스레드 안전하지 않으므로 핸들에 직접 손대지 않고 [GitAccess] 를 통해서만
 * 접근한다. 직렬화와 `Dispatchers.IO` 실행이 거기 모여 있어 — 이 클래스는 락도 `withContext` 도
 * 다시 걸지 않는다. 조회 단위로 여는 JGit 자원은 모두 `use {}` 로 닫는다.
 */
class PatchGatewayImpl(
    private val gitAccess: GitAccess,
    /**
     * 승격 직후의 검증 지점. 기본 구현은 워킹트리가 결과 트리와 실제로 같아졌는지 확인한다.
     * 여기서 실패가 나면 기록한 트리로 되돌린다 — 복원 경로를 테스트가 실제 저장소로 검증할 수 있도록
     * 주입 가능하게 둔다.
     */
    private val verifyPromotion: (Repository, ObjectId, List<String>) -> Unit = ::verifyWorkingTreeMatches,
) : PatchGateway {

    override suspend fun export(range: CommitRange): PatchExport =
        patchOperation("export.range") { repository -> repository.exportRange(range) }

    override suspend fun export(scope: WorkingTreeScope): PatchExport =
        patchOperation("export.scope") { repository -> repository.exportScope(scope) }

    override suspend fun dryRun(patch: ByteArray, mode: ApplyMode): ApplyOutcome =
        patchOperation("dryRun") { repository -> repository.runPatch(patch, mode, promote = false) }

    override suspend fun apply(patch: ByteArray, mode: ApplyMode): ApplyOutcome =
        patchOperation("apply") { repository -> repository.runPatch(patch, mode, promote = true) }

    override suspend fun applyReversed(patch: ByteArray): ApplyOutcome =
        patchOperation("applyReversed") { repository -> repository.runReversed(patch) }

    /**
     * 역방향 적용. 뒤집은 패치를 **정방향과 같은 경로**로 흘려보낸다 —
     * 역방향 전용 적용 코드를 따로 두면 원자성 보장이 그쪽에만 빠진다.
     */
    private fun Repository.runReversed(patch: ByteArray): ApplyOutcome {
        val files = parsePatchFiles(patch)
        return when {
            files.isEmpty() -> ApplyOutcome.NoChange
            files.hasNonHunkChange() -> ApplyOutcome.Unsupported(UnsupportedReason.REVERSE_NOT_PURE_HUNK)
            else -> runPatch(reversePatchBytes(patch), ApplyMode.WorkingTreeOnly, promote = true)
        }
    }

    private fun Repository.runPatch(patch: ByteArray, mode: ApplyMode, promote: Boolean): ApplyOutcome {
        val files = parsePatchFiles(patch)
        if (files.isEmpty()) return ApplyOutcome.NoChange
        val patchPaths = files.affectedPaths()
        requireSafePatchPaths(patchPaths)
        val paths = transactionPathsFor(patchPaths)
        requireRestorableTarget(paths)
        // 커밋 메타데이터가 없으면 **아무것도 적용하기 전에** 거부한다 — 적용해 놓고 커밋에서 막히면
        // 사용자는 자기가 요청하지 않은 중간 상태를 떠안는다.
        val request = PatchApplyRequest(
            mode = mode,
            commit = (mode as? ApplyMode.CreateCommit)?.let { create -> resolveCommitRequest(patch, create) },
            promote = promote,
        )
        return newObjectInserter().use { inserter ->
            val transaction = recordPatchTree(paths, inserter)
            applyAndPromote(transaction, PatchSource(patch, files), inserter, request)
        }
    }

    private fun Repository.applyAndPromote(
        transaction: PatchTreeTransaction,
        source: PatchSource,
        inserter: ObjectInserter,
        request: PatchApplyRequest,
    ): ApplyOutcome =
        when (val isolated = applyIsolated(transaction.treeId, source.bytes, source.files, inserter)) {
            is IsolatedApplication.Rejected -> isolated.outcome
            is IsolatedApplication.Applied -> when {
                isolated.treeId == transaction.treeId -> ApplyOutcome.NoChange
                !request.promote -> ApplyOutcome.Applied(isolated.paths)
                else -> promoteOrRestore(transaction, isolated, request)
            }
        }

    /**
     * 격리 적용을 통과한 트리를 실제 워킹트리로 승격한다.
     *
     * 실패하면 기록한 트리로 되돌린 뒤 원래 실패를 그대로 올린다. 복원 자체가 실패하면 원인을
     * 가리지 않도록 suppressed 로 덧붙인다 — 복원 실패가 적용 실패를 덮으면 진단이 불가능해진다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun Repository.promoteOrRestore(
        transaction: PatchTreeTransaction,
        isolated: IsolatedApplication.Applied,
        request: PatchApplyRequest,
    ): ApplyOutcome {
        try {
            materialize(isolated.treeId, transaction.paths)
            if (request.mode !is ApplyMode.WorkingTreeOnly) {
                writeIndexEntriesFrom(isolated.treeId, transaction.paths)
            }
            verifyPromotion(this, isolated.treeId, transaction.paths)
            request.commit?.let { commit -> createPatchCommit(transaction.paths, commit) }
        } catch (failure: Throwable) {
            runCatching { transaction.restore() }.onFailure(failure::addSuppressed)
            throw failure
        }
        return ApplyOutcome.Applied(isolated.paths)
    }

    /** 모든 진입점이 지나는 단 하나의 통로다 — 여기 밖에서 `Repository` 를 만지면 직렬화가 깨진다. */
    private suspend fun <T> patchOperation(operation: String, block: (Repository) -> T): T =
        gitAccess.withSequence { repository ->
            runCatching { block(repository) }
                .getOrElse { failure -> translatePatchFailure(operation, failure) }
        }
}

/** 원본 바이트와 그것을 읽은 결과. 격리 적용은 둘 다 필요하다 — 전체 적용에 바이트, 실패 귀속에 파일 목록. */
private data class PatchSource(val bytes: ByteArray, val files: List<FileHeader>) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PatchSource && bytes.contentEquals(other.bytes) && files == other.files)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + files.hashCode()
}

/** 한 번의 적용이 무엇을 해야 하는지. 인자 여섯 개를 줄줄이 넘기면 자리 하나가 바뀌어도 컴파일된다. */
private data class PatchApplyRequest(
    val mode: ApplyMode,
    val commit: CommitRequest?,
    val promote: Boolean,
)

/**
 * [paths] 만 담아 커밋한다. `commit()` 은 인덱스 전체를 담으므로, 사용자가 따로 올려 둔 변경까지
 * 이 커밋에 섞여 들어간다.
 *
 * 작성자와 committer 를 모두 명시한다 — 설정에 `user.name` 이 없어도 패치 적용이 실패하지 않아야 하고,
 * 어느 쪽도 이 시점에 지어낼 값이 아니다 (둘 다 [resolveCommitRequest] 가 확정했다).
 */
private fun Repository.createPatchCommit(paths: List<String>, request: CommitRequest) {
    val identity = PersonIdent(request.author.name, request.author.email)
    Git(this).use { git ->
        val commit = git.commit().setMessage(request.message).setAuthor(identity).setCommitter(identity)
        paths.forEach(commit::setOnly)
        commit.call()
    }
}

/**
 * 승격이 실제로 끝났는지 확인한다. 트리는 적용됐다고 말하는데 워킹트리가 그 말과 다르면,
 * 그 상태를 성공으로 보고하는 것이 가장 나쁜 결과다 — 되돌린 뒤 실패로 올린다.
 */
internal fun verifyWorkingTreeMatches(repository: Repository, treeId: ObjectId, paths: List<String>) {
    val expected = repository.readTreeBlobs(treeId, paths)
    if (repository.workingTreeFilesUnder(paths) != expected.keys) {
        throw UndineException.GitOperationFailed("patch.promote")
    }
    val mismatched = expected.filterNot { (path, blob) -> repository.workingTreeMatches(path, blob) }
    if (mismatched.isNotEmpty()) {
        throw UndineException.GitOperationFailed("patch.promote")
    }
}

/**
 * 패치 연산에서 올라온 실패를 도메인 예외로 번역한다.
 *
 * - `CancellationException` 은 삼키지 않고 그대로 올린다 — 삼키면 코루틴 취소가 동작하지 않는다.
 * - `IllegalArgumentException` 은 호출부 버그(사전조건 위반)이므로 사용자 메시지로 번역하지 않는다.
 * - 이미 도메인 예외인 것은 다시 감싸지 않는다.
 * - 그 밖의 실패만 `GitOperationFailed("patch.<op>", cause)` 로 감싼다 — 최후 수단이다.
 */
internal fun translatePatchFailure(operation: String, failure: Throwable): Nothing =
    when (failure) {
        is CancellationException, is UndineException, is IllegalArgumentException -> throw failure
        else -> throw UndineException.GitOperationFailed("patch.$operation", failure)
    }
