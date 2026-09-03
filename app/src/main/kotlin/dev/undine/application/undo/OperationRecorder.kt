package dev.undine.application.undo

import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.ChangeRecordingOrder
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

private val LOGGER: Logger = Logger.getLogger("dev.undine.application.undo.OperationRecorder")

/**
 * 되돌릴 지점을 확보하지 못했을 때 남기는 사유. 브랜치 위가 아니거나(detached HEAD) 변경 직전
 * 지점이 없는(첫 커밋) 경우이며, 화면이 그대로 보여줄 수 있는 문장이다.
 */
private const val NO_UNDO_POINT_REASON =
    "변경 직전 지점을 확보하지 못해 되돌릴 수 없습니다 — reflog 에서 이전 위치를 찾으세요."

/**
 * 변경 연산이 끝난 직후 그 연산의 되돌리기 정보를 세션 스택에 남긴다.
 *
 * 어느 연산을 언제 기록할지는 각 연산 UseCase 의 배선(UND-51)이 맡고, 이 클래스는 한 기록을
 * 원자적으로 만든다.
 *
 * 기록 시각은 여기서 읽는다. [clock]은 배선이 주입하며 기본값은 시스템 시계다 —
 * 실행 이력의 시각 표시가 테스트에서 결정적이어야 하기 때문에 `Instant.now()`를 직접 부르지 않는다.
 */
class OperationRecorder(
    private val refGateway: RefGateway,
    private val undoStack: UndoStack,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val changeRecordingOrder: ChangeRecordingOrder,
) {

    /**
     * Git 변경과 그 Undo 기록을 저장소가 소유한 같은 순서로 완료한다.
     *
     * application이 `GitAccess`를 직접 알면 레이어가 역전되므로, 그 구현이 제공하는 domain 계약만
     * 받는다. 순번을 결과에 넣어 UndoStack에서 재정렬하는 방법은 이미 기록 중인 producer와 용량
     * 축출까지 모두 바꿔야 하므로, 기록이 끝날 때까지 다음 변경을 들이지 않는 쪽을 택했다 (G32).
     *
     * [changeRecordingOrder]에 **기본값을 두지 않는다** (결정 G5, UND-85). 기본값이 있으면 주입을
     * 빠뜨린 새 조립 지점에서 순서 보장이 아무 경고 없이 꺼진다 — 그때 남는 기록은 최상단의 기준
     * 상태가 낡아 되돌리기가 거부된다. 순서를 일부러 걸지 않는 단위 테스트는 통과 구현을 명시적으로
     * 넘겨 "없어서 안 걸었다"와 구분한다.
     */
    suspend fun <T> recordingChange(block: suspend () -> T): T =
        changeRecordingOrder.withOrderedChange(block)

    /**
     * 되돌릴 수 있는 연산과 **그 변경이 준 기준 상태**를 함께 기록한다.
     *
     * [baseline]을 인자로 받는 것이 이 경로의 핵심이다 (UND-73). 여기서 `RefGateway` 로 다시 읽으면
     * 그 읽기가 변경과 다른 임계 구역이라, 사이에 앱 내부의 다른 Git 조작이 끼어들면 "내 변경 직후"가
     * 아니라 남의 변경까지 반영된 상태가 남는다. 그러면 되돌리기 직전의
     * `OperationEntry.planUndo` 비교가 오염돼 **거부해야 할 때 통과**하고, 남의 변경 위에서 되돌리기가
     * 실행된다. 그래서 변경 연산이 자기 임계 구역 안에서 캡처한 값을 호출자가 그대로 전달한다.
     *
     * @param baseline 이 연산을 수행한 Gateway 결과가 준 변경 직후 기준 상태.
     * @param targetLabel 이력 패널이 보여줄 대상 한 줄(커밋 제목·브랜치 이름 등). 호출부가 더
     *   구체적인 이름을 모르면 연산 이름이 최소 표시값으로 들어간다 — 빈 칸을 남기지 않기 위해서다.
     */
    suspend fun record(
        operation: GitOperationKind,
        strategy: UndoStrategy.Reversible,
        baseline: RepositoryBaseline,
        targetLabel: String = operation.label,
    ): OperationEntry = recordEntry(operation, strategy, baseline, targetLabel)

    /**
     * 복구 불가 연산도 버리지 않고 사람이 읽을 수 있는 사유와 함께 기록한다.
     *
     * push·hard reset·stash drop은 이 경로로 남겨야 Undo 버튼이 눌렸을 때 조용히 성공한 것처럼
     * 보이지 않는다.
     *
     * **기준 상태는 여기서 읽는다.** 복구 불가 기록은 `planUndo` 가 기준 상태를 비교하기 **전에**
     * 사유를 붙여 거부하므로 그 값이 되돌리기 판단에 끼어들지 않는다 — 닫아야 할 창이 없다. 반대로
     * 이 경로까지 값을 요구하면 되돌릴 수 없는 연산의 Gateway 계약을 모두 넓혀야 하는데, 얻는 안전이
     * 없다 (결정 A-J 3, wave 8 결정 G9).
     *
     * **이 예외는 분기 순서에 기댄다.** `OperationEntry.planUndo` 가 `UndoStrategy.Irreversible` 을
     * **가장 먼저** 걸러 내는 동안에만 성립한다. 그 순서가 바뀌어 복구 불가 항목의 baseline 이
     * `current != baseline` 비교에 닿게 되면 여기서 읽은 값이 되돌리기 판단을 오염시키므로,
     * **결정 G9 를 다시 보고 이 경로도 [record] 처럼 baseline 을 인자로 받도록 고쳐야 한다.**
     */
    suspend fun recordIrreversible(
        operation: GitOperationKind,
        reason: String,
        targetLabel: String = operation.label,
    ): OperationEntry =
        recordEntry(operation, UndoStrategy.Irreversible(reason), refGateway.currentBaseline(), targetLabel)

    /**
     * 기록 실패를 **저장소 변경 실패로 승격하지 않고** 사유를 호출자에게 돌려준다.
     *
     * 이 메서드가 여기 있는 이유는 계약의 소유자가 기록하는 객체이기 때문이다. 규칙 8
     * (`.agent/rules/exception-handling.md`)이 "Git 변경은 성공으로 돌려주되 기록 실패 사유를 결과에
     * 실어라" 를 요구하는데, 그 처리를 호출부마다 복제하면 여덟 벌이 되고 한 벌만 어긋나도 실패가
     * 조용히 사라진다 (결정 G30 2).
     *
     * [CancellationException] 은 다시 던진다 — 호출부는 [kotlinx.coroutines.NonCancellable] 구간에서
     * 이 경로를 지나므로, 여기까지 올라온 취소는 기록 자체의 실패가 아니다. 삼키면 취소가 동작하지 않는다.
     */
    suspend fun recordQuietly(
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

    /**
     * 변경 **직전** HEAD 로 soft reset 하는 되돌리기를 [recordQuietly] 경로로 남긴다.
     *
     * [previousHead] 나 [baseline] 이 되돌릴 지점을 주지 못하면(첫 커밋·detached HEAD) 기록을 건너뛰지
     * 않고 사유와 함께 복구 불가로 남긴다 — 건너뛰면 사용자는 그 변경이 있었다는 사실조차 모른다.
     */
    suspend fun recordSoftReset(
        operation: GitOperationKind,
        previousHead: CommitId?,
        baseline: RepositoryBaseline,
        targetLabel: String = operation.label,
    ): UndineException? = recordQuietly(operation) {
        if (previousHead == null || !baseline.isOnBranch) {
            recordIrreversible(operation, NO_UNDO_POINT_REASON, targetLabel)
        } else {
            record(operation, UndoStrategy.SoftResetTo(previousHead), baseline, targetLabel)
        }
    }

    /**
     * 변경 **직전** 지점으로 hard reset 하는 되돌리기를 [recordQuietly] 경로로 남긴다.
     *
     * `branch` 와 `expected` 는 [baseline] 이 준다 — 변경과 같은 임계 구역에서 캡처한 값이므로
     * "이 연산이 만든 위치" 를 정확히 가리킨다. 되돌리기는 브랜치가 여전히 그 위치일 때만 수행하며,
     * 그래서 `expected` 에 기본값을 두지 않는다 (결정 G5).
     */
    suspend fun recordHardReset(
        operation: GitOperationKind,
        previousHead: CommitId?,
        baseline: RepositoryBaseline,
        targetLabel: String = operation.label,
    ): UndineException? = recordQuietly(operation) {
        val branch = baseline.branch
        val expected = baseline.head
        if (previousHead == null || branch == null || expected == null) {
            recordIrreversible(operation, NO_UNDO_POINT_REASON, targetLabel)
        } else {
            record(operation, UndoStrategy.HardResetTo(branch, previousHead, expected), baseline, targetLabel)
        }
    }

    private suspend fun recordEntry(
        operation: GitOperationKind,
        strategy: UndoStrategy,
        baseline: RepositoryBaseline,
        targetLabel: String,
    ): OperationEntry {
        val entry = OperationEntry(
            operation = operation,
            strategy = strategy,
            baseline = baseline,
            targetLabel = targetLabel,
            recordedAt = clock.instant(),
        )
        undoStack.record(entry)
        return entry
    }
}
