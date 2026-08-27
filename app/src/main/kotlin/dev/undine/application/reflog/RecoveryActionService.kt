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
import kotlinx.coroutines.CancellationException
import java.util.logging.Level
import java.util.logging.Logger

private val LOGGER: Logger = Logger.getLogger("dev.undine.application.reflog.RecoveryActionService")

/** Reflog 목록과 만료 가능성. 빈 목록은 실패가 아니라 실제 조회 결과다. */
data class ReflogListing(val entries: List<ReflogEntry>, val mayBeExpired: Boolean)

/** 선택한 reflog 지점의 커밋 메시지와 첫 부모 기준 변경 파일 요약. */
data class ReflogCommitPreview(val commit: Commit, val changedFiles: List<FileChange>)

/**
 * 이미 적용된 변경의 결과와, 그 변경의 Undo 기록이 실패했다면 그 사유.
 *
 * [undoRecordFailure]가 null이 아니면 **저장소 변경은 성공했고 Undo 항목만 남지 않았다.** 둘을
 * 한 값으로 묶어 돌려주므로 화면은 "실패"와 "적용됐지만 되돌릴 수 없음"을 구분해 알릴 수 있다.
 * 기록 실패를 여기 담지 않고 로그로만 남기면 사용자는 Undo 목록에서 항목이 사라진 사실을 모른다.
 */
data class RecoveryOutcome<out T>(val value: T, val undoRecordFailure: UndineException?)

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

    suspend fun recover(commit: CommitId, target: RecoveryTarget): RecoveryOutcome<RefName>

    suspend fun restoreBisect(): BisectSession?

    suspend fun startBisect(good: CommitId, bad: CommitId): RecoveryOutcome<BisectResult>

    suspend fun markBisect(verdict: BisectVerdict): RecoveryOutcome<BisectResult>

    suspend fun resetBisect(): RecoveryOutcome<Unit>
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

    /**
     * 되돌리기의 기준 상태는 **복구 결과가 준 값**을 쓴다 (UND-73). 복구가 끝난 뒤 여기서 따로 읽으면
     * 그 사이에 앱 내부의 다른 Git 조작이 끼어들어 "내 복구 직후" 가 아닌 상태가 기록되고, 되돌리기
     * 직전의 외부 변경 비교가 오염된다.
     */
    override suspend fun recover(commit: CommitId, target: RecoveryTarget): RecoveryOutcome<RefName> {
        val recovered = reflogGateway.recover(commit, target)
        val recordFailure = recordQuietly(GitOperationKind.REFLOG_RESTORE) {
            when (target) {
                is RecoveryTarget.NewBranch -> operationRecorder.record(
                    GitOperationKind.REFLOG_RESTORE,
                    UndoStrategy.DeleteBranch(recovered.ref),
                    recovered.baseline,
                )

                is RecoveryTarget.MoveExisting -> operationRecorder.recordIrreversible(
                    GitOperationKind.REFLOG_RESTORE,
                    "${target.name.value}을(를) 이동해 " +
                        "${target.confirmation.displacedCommit.value}의 기존 위치를 덮어썼습니다.",
                )
            }
        }
        return RecoveryOutcome(recovered.ref, recordFailure)
    }

    override suspend fun restoreBisect(): BisectSession? = bisect.restore.execute()

    override suspend fun startBisect(good: CommitId, bad: CommitId): RecoveryOutcome<BisectResult> =
        bisect.start.execute(good, bad).let { RecoveryOutcome(it, recordBisectChange()) }

    override suspend fun markBisect(verdict: BisectVerdict): RecoveryOutcome<BisectResult> =
        bisect.mark.execute(verdict).let { RecoveryOutcome(it, recordBisectChange()) }

    override suspend fun resetBisect(): RecoveryOutcome<Unit> {
        bisect.reset.execute()
        return RecoveryOutcome(Unit, recordBisectChange())
    }

    /** UndoService에는 bisect 상태 파일을 복원하는 전략이 없으므로 화면의 reset 경로를 안내한다. */
    private suspend fun recordBisectChange(): UndineException? =
        recordQuietly(GitOperationKind.BISECT_SESSION) {
            operationRecorder.recordIrreversible(
                GitOperationKind.BISECT_SESSION,
                "bisect 세션은 Recovery 화면의 reset으로만 시작 지점으로 복구할 수 있습니다.",
            )
        }

    /**
     * Undo 기록 실패를 저장소 변경 실패로 승격하지 않고, **사유를 호출자에게 돌려준다.**
     *
     * 여기 오는 시점에 Git 변경은 **이미 적용돼 있다.** 기록이 실패했다고 예외를 올리면 화면은
     * "실패" 를 보여 주는데 저장소는 바뀐 상태가 되어, 사용자가 되돌릴 대상도 이어갈 대상도 잃는다.
     * 그렇다고 로그로만 삼키면 Undo 항목이 없어진 사실이 화면에 닿지 않는다 — 강등이지 은폐가
     * 아니므로 실패를 [RecoveryOutcome.undoRecordFailure]로 올려 화면이 알리게 한다.
     */
    private suspend fun recordQuietly(
        operation: GitOperationKind,
        record: suspend () -> Unit,
    ): UndineException? =
        try {
            record()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            LOGGER.log(Level.WARNING, "undo record failed after applied change: operation=$operation", failure)
            failure
        }
}

private const val FIRST_PARENT_INDEX = 0
