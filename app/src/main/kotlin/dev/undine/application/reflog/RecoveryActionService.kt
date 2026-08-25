package dev.undine.application.reflog

import dev.undine.application.bisect.MarkBisectUseCase
import dev.undine.application.bisect.ResetBisectUseCase
import dev.undine.application.bisect.RestoreBisectSessionUseCase
import dev.undine.application.bisect.StartBisectUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.FileChange
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.ReflogGateway
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy

/** Reflog 목록과 만료 가능성. 빈 목록은 실패가 아니라 실제 조회 결과다. */
data class ReflogListing(val entries: List<ReflogEntry>, val mayBeExpired: Boolean)

/** 선택한 reflog 지점의 커밋 메시지와 첫 부모 기준 변경 파일 요약. */
data class ReflogCommitPreview(val commit: Commit, val changedFiles: List<FileChange>)

/**
 * Recovery 화면이 호출하는 application 경계.
 *
 * presentation은 이 인터페이스만 알고 Gateway와 JGit 구현을 직접 알지 않는다. bisect의 규칙은 이미
 * 존재하는 UseCase에 맡기며, 여기서는 화면 동작에 필요한 reflog 조회·미리보기·Undo 기록만 조합한다.
 */
interface RecoveryActions {
    suspend fun loadReflog(limit: Int): ReflogListing

    suspend fun loadPreview(commit: CommitId): ReflogCommitPreview

    suspend fun scanUnreachable(limit: Int): UnreachableCommitScan

    suspend fun recover(commit: CommitId, target: RecoveryTarget): RefName

    suspend fun restoreBisect(): BisectSession?

    suspend fun startBisect(good: CommitId, bad: CommitId): BisectResult

    suspend fun markBisect(verdict: BisectVerdict): BisectResult

    suspend fun resetBisect()
}

/** 기존 bisect UseCase 묶음. Recovery 화면이 필요한 네 동작을 한 의존성으로 전달한다. */
data class RecoveryBisectUseCases(
    val start: StartBisectUseCase,
    val mark: MarkBisectUseCase,
    val reset: ResetBisectUseCase,
    val restore: RestoreBisectSessionUseCase,
)

/**
 * Recovery 화면용 동작 구현.
 *
 * `HistoryGateway.load`는 ref 이름뿐 아니라 object id도 resolve할 수 있으므로, reflog 단건 조회
 * 계약을 새로 만들지 않고 [CommitId.value]를 [RefName]으로 전달한다. 실제 object DB 조회와 실패
 * 번역은 기존 HistoryGateway 구현이 소유한다.
 */
class RecoveryActionService(
    private val reflogGateway: ReflogGateway,
    private val historyGateway: HistoryGateway,
    private val diffGateway: DiffGateway,
    private val bisect: RecoveryBisectUseCases,
    private val operationRecorder: OperationRecorder,
) : RecoveryActions {

    override suspend fun loadReflog(limit: Int): ReflogListing =
        reflogGateway.headReflog(limit).let { page -> ReflogListing(page.entries, page.mayBeExpired) }

    override suspend fun loadPreview(commit: CommitId): ReflogCommitPreview {
        val selected = historyGateway.load(refs = listOf(RefName(commit.value)), offset = 0, limit = 1)
            .singleOrNull()
            ?: throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
        return ReflogCommitPreview(selected, diffGateway.changedFiles(commit, FIRST_PARENT_INDEX))
    }

    override suspend fun scanUnreachable(limit: Int): UnreachableCommitScan =
        reflogGateway.unreachableCommits(limit)

    override suspend fun recover(commit: CommitId, target: RecoveryTarget): RefName {
        val recovered = reflogGateway.recover(commit, target)
        when (target) {
            is RecoveryTarget.NewBranch -> operationRecorder.record(
                GitOperationKind.REFLOG_RESTORE,
                UndoStrategy.DeleteBranch(recovered),
            )

            is RecoveryTarget.MoveExisting -> operationRecorder.recordIrreversible(
                GitOperationKind.REFLOG_RESTORE,
                "${target.name.value}을(를) 이동해 ${target.confirmation.displacedCommit.value}의 기존 위치를 덮어썼습니다.",
            )
        }
        return recovered
    }

    override suspend fun restoreBisect(): BisectSession? = bisect.restore.execute()

    override suspend fun startBisect(good: CommitId, bad: CommitId): BisectResult =
        bisect.start.execute(good, bad).also { recordBisectChange() }

    override suspend fun markBisect(verdict: BisectVerdict): BisectResult =
        bisect.mark.execute(verdict).also { recordBisectChange() }

    override suspend fun resetBisect() {
        bisect.reset.execute()
        recordBisectChange()
    }

    /** UndoService에는 bisect 상태 파일을 복원하는 전략이 없으므로 화면의 reset 경로를 안내한다. */
    private suspend fun recordBisectChange() {
        operationRecorder.recordIrreversible(
            GitOperationKind.BISECT_SESSION,
            "bisect 세션은 Recovery 화면의 reset으로만 시작 지점으로 복구할 수 있습니다.",
        )
    }
}

private const val FIRST_PARENT_INDEX = 0
