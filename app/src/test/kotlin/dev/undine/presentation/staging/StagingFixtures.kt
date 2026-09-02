package dev.undine.presentation.staging

import dev.undine.application.staging.AmendCommitUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.staging.StageHunksUseCase
import dev.undine.application.staging.UnstageFilesUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.AmendConfirmation
import dev.undine.domain.AmendPreflight
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.DiffHunk
import dev.undine.domain.FileChange
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.undo.UndoStack
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.recorderOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal fun commitId(seed: String): CommitId = CommitId.of(seed.padEnd(40, '0'))

internal fun fileChange(path: String, changeType: ChangeType = ChangeType.MODIFIED): FileChange =
    FileChange(
        path = path,
        previousPath = null,
        changeType = changeType,
        addedLines = 1,
        deletedLines = 0,
        isBinary = false,
    )

internal fun statusOf(
    staged: List<String> = emptyList(),
    unstaged: List<String> = emptyList(),
    untracked: List<String> = emptyList(),
    conflicted: List<String> = emptyList(),
): WorkingTreeStatus = WorkingTreeStatus(
    staged = staged.map { fileChange(it) },
    unstaged = unstaged.map { fileChange(it) },
    untracked = untracked,
    conflicted = conflicted,
)

/** 워킹트리 상태만 돌려주는 대역. 조작 뒤 목록이 바뀌는 것을 흉내 내려면 [statusQueue] 를 준다. */
internal class FakeRepositoryGateway(
    private val statusQueue: MutableList<WorkingTreeStatus>,
) : RepositoryGateway {

    constructor(vararg statuses: WorkingTreeStatus) : this(statuses.toMutableList())

    override suspend fun open(path: RepositoryPath): OpenedRepository = error("사용하지 않는다")

    /** 큐가 하나 남으면 그것을 계속 돌려준다 — 조작 없는 재조회가 큐를 소모하지 않게 한다. */
    override suspend fun status(): WorkingTreeStatus =
        if (statusQueue.size > 1) statusQueue.removeAt(0) else statusQueue.first()

    override suspend fun close() = error("사용하지 않는다")
}

/**
 * 스테이징 조작을 기록하는 대역.
 *
 * @param commitFailure `commit` 이 던질 예외. 작성자 미설정·빈 스테이징 경로를 재현한다.
 * @param amendExistsOnRemote `inspectAmend` 가 원격 포함으로 답할지 — 확인 절차 분기를 만든다.
 */
internal class RecordingStagingGateway(
    private val commitFailure: UndineException? = null,
    private val amendExistsOnRemote: Boolean = false,
    private val amendTarget: CommitId = commitId("a"),
) : StagingGateway {

    val staged = mutableListOf<List<String>>()
    val unstaged = mutableListOf<List<String>>()
    val hunkRequests = mutableListOf<Pair<String, List<DiffHunk>>>()
    val commitMessages = mutableListOf<String>()
    val amendMessages = mutableListOf<Pair<String, AmendConfirmation>>()

    override suspend fun stage(paths: List<String>) {
        staged += paths
    }

    override suspend fun unstage(paths: List<String>) {
        unstaged += paths
    }

    override suspend fun stageHunks(path: String, hunks: List<DiffHunk>) {
        hunkRequests += path to hunks
    }

    override suspend fun commit(message: String): CommitResult {
        commitFailure?.let { throw it }
        commitMessages += message
        return committed("c")
    }

    /** 결합 연산도 같은 장부에 남긴다 — 나눠 부른 것과 구분해서 확인할 수 있어야 한다. */
    override suspend fun stageAndCommit(paths: List<String>, message: String): CommitResult {
        commitFailure?.let { throw it }
        staged += paths
        commitMessages += message
        return committed("c")
    }

    override suspend fun inspectAmend(): AmendPreflight =
        AmendPreflight(target = amendTarget, existsOnRemote = amendExistsOnRemote)

    override suspend fun amend(message: String, confirmation: AmendConfirmation): CommitResult {
        amendMessages += message to confirmation
        return committed("d")
    }
}

/**
 * 대역으로 만든 상태 홀더. `Dispatchers.Unconfined` 라 `launch` 가 호출 지점에서 그대로 실행돼
 * 테스트가 시간에 의존하지 않는다.
 */
internal fun stagingStateWith(
    repositoryGateway: RepositoryGateway,
    stagingGateway: StagingGateway,
    recorder: OperationRecorder = recorderOf(UndoStack()),
): StagingState = StagingState(
    actions = StagingActions(
        loadStatus = LoadWorkingTreeStatusUseCase(repositoryGateway),
        stageFiles = StageFilesUseCase(stagingGateway),
        unstageFiles = UnstageFilesUseCase(stagingGateway),
        stageHunks = StageHunksUseCase(stagingGateway),
        commitStaged = CommitStagedUseCase(stagingGateway, recorder),
        amendCommit = AmendCommitUseCase(stagingGateway, recorder),
    ),
    scope = CoroutineScope(Dispatchers.Unconfined),
)

/** 커밋 결과가 싣는 되돌리기 재료 (UND-73). 화면은 그 값을 통과시키기만 한다. */
private fun committed(seed: String): CommitResult =
    CommitResult(commitId(seed), previousHead = commitId("b"), baseline = baselineOf(commitId(seed)))
