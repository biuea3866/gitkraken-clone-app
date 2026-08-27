package dev.undine.application.undo

import dev.undine.domain.RefGateway
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import java.time.Clock

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
) {

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
