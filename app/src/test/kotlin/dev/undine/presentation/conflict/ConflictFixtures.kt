package dev.undine.presentation.conflict

import dev.undine.application.conflict.AbortConflictedOperationUseCase
import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.conflict.LoadConflictContentUseCase
import dev.undine.application.conflict.LoadConflictedFilesUseCase
import dev.undine.application.conflict.ResolveConflictUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation
import dev.undine.testsupport.commitId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 충돌 **화면** 테스트와 스크린샷 렌더가 공유하는 대역.
 *
 * `ConflictStateSpec` 의 대역과 따로 두는 이유는 검증 대상이 다르기 때문이다 — 그쪽은 상태 홀더의
 * 전이를, 여기는 화면이 무엇을 그리고 어떤 호출을 만드는지를 본다.
 */
internal fun conflictStateWith(
    conflict: RecordingConflictGateway,
    repository: FixedStatusRepositoryGateway = FixedStatusRepositoryGateway(),
    merge: StubMergeGateway = StubMergeGateway(),
): ConflictState {
    val mergeService = MergeService(repository, merge)
    return ConflictState(
        actions = ConflictActions(
            loadFiles = LoadConflictedFilesUseCase(conflict),
            loadContent = LoadConflictContentUseCase(conflict),
            resolve = ResolveConflictUseCase(conflict),
            continueAfterResolve = ContinueAfterResolveUseCase(mergeService),
            abort = AbortConflictedOperationUseCase(mergeService),
            loadStatus = LoadWorkingTreeStatusUseCase(repository),
        ),
        repositoryState = { RepositoryState.MERGING },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )
}

/** 스크린샷 렌더용 지름길 — 기록은 보지 않고 그릴 내용만 준다. */
internal fun conflictStateForRender(
    files: List<ConflictedFile>,
    contents: Map<String, String>,
): ConflictState = conflictStateWith(RecordingConflictGateway(files, contents))

/** 충돌 목록·내용을 주고 해결 호출을 기록하는 대역. */
internal class RecordingConflictGateway(
    private var files: List<ConflictedFile>,
    private val contents: Map<String, String>,
) : ConflictGateway {

    /** 해결로 워킹트리에 쓴 내용. 화면이 저장까지 갔는지 여기서 본다. */
    val resolvedContents = mutableMapOf<String, String>()

    /** 이진 파일에서 채택한 쪽. */
    val binaryChoices = mutableMapOf<String, ConflictSide>()

    override suspend fun listConflicted(): List<ConflictedFile> = files

    override suspend fun readConflicted(path: String): String = contents.getValue(path)

    override suspend fun resolve(path: String, content: String) {
        resolvedContents[path] = content
        // 해결한 파일은 다음 조회에서 빠진다 — 실제 Gateway 도 인덱스에 올라간 파일을 내지 않는다.
        files = files.filterNot { it.path == path }
    }

    override suspend fun resolveBinary(path: String, side: ConflictSide) {
        binaryChoices[path] = side
        files = files.filterNot { it.path == path }
    }
}

/** 고정된 워킹트리 상태만 답하는 저장소 대역. 중단 확인 목록의 재료다. */
internal class FixedStatusRepositoryGateway(
    private val unstaged: List<String> = emptyList(),
    private val conflicted: List<String> = emptyList(),
) : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository =
        OpenedRepository(state = RepositoryState.MERGING, currentBranch = RefName("refs/heads/main"))

    override suspend fun status(): WorkingTreeStatus = WorkingTreeStatus(
        staged = emptyList(),
        unstaged = unstaged.map { path ->
            FileChange(path, null, ChangeType.MODIFIED, 1, 0, isBinary = false)
        },
        untracked = emptyList(),
        conflicted = conflicted,
    )

    override suspend fun close() = Unit
}

/** 병합 실행 호출만 기록하는 대역. 리베이스 경로는 이 화면 테스트가 쓰지 않는다. */
internal class StubMergeGateway(
    private val state: RepositoryState = RepositoryState.MERGING,
) : MergeGateway {

    val calls = mutableListOf<String>()

    override suspend fun repositoryState(): RepositoryState = state

    override suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult =
        error("화면은 병합을 시작하지 않는다")

    override suspend fun continueMerge(): MergeResult {
        calls += "continueMerge"
        return MergeResult.Succeeded(commitId(1), fastForward = false)
    }

    override suspend fun abortMerge(confirmation: AbortConfirmation) {
        calls += "abortMerge"
    }

    override suspend fun rebase(target: RefName): RebaseResult =
        error("화면은 리베이스를 시작하지 않는다")

    override suspend fun continueRebase(): RebaseResult {
        calls += "continueRebase"
        return RebaseResult.Succeeded(commitId(1))
    }

    override suspend fun rebasingCommit(): CommitId? = null

    override suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult =
        error("화면은 건너뛰기를 제공하지 않는다")

    override suspend fun abortRebase(confirmation: AbortConfirmation) {
        calls += "abortRebase"
    }
}
