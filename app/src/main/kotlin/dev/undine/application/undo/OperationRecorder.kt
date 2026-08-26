package dev.undine.application.undo

import dev.undine.domain.RefGateway
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import java.time.Clock

/**
 * 변경 연산이 끝난 직후 그 연산의 되돌리기 정보를 세션 스택에 남긴다.
 *
 * 기준 상태를 호출자에게 맡기지 않고 여기서 읽는다. 호출자가 기준 상태 기록을 빼먹으면 이후
 * [UndoService]가 앱 밖의 변경을 안전하게 감지할 수 없기 때문이다. 어느 연산을 언제 기록할지는
 * 각 연산 UseCase의 배선(UND-51)이 맡고, 이 클래스는 한 기록을 원자적으로 만든다.
 *
 * 기록 시각도 같은 이유로 여기서 읽는다. [clock]은 배선이 주입하며 기본값은 시스템 시계다 —
 * 실행 이력의 시각 표시가 테스트에서 결정적이어야 하기 때문에 `Instant.now()`를 직접 부르지 않는다.
 */
class OperationRecorder(
    private val refGateway: RefGateway,
    private val undoStack: UndoStack,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * 되돌릴 수 있는 연산과 그 시점의 기준 상태를 함께 기록한다.
     *
     * @param targetLabel 이력 패널이 보여줄 대상 한 줄(커밋 제목·브랜치 이름 등). 호출부가 더
     *   구체적인 이름을 모르면 연산 이름이 최소 표시값으로 들어간다 — 빈 칸을 남기지 않기 위해서다.
     */
    suspend fun record(
        operation: GitOperationKind,
        strategy: UndoStrategy.Reversible,
        targetLabel: String = operation.label,
    ): OperationEntry = recordEntry(operation, strategy, targetLabel)

    /**
     * 복구 불가 연산도 버리지 않고 사람이 읽을 수 있는 사유와 함께 기록한다.
     *
     * push·hard reset·stash drop은 이 경로로 남겨야 Undo 버튼이 눌렸을 때 조용히 성공한 것처럼
     * 보이지 않는다.
     */
    suspend fun recordIrreversible(
        operation: GitOperationKind,
        reason: String,
        targetLabel: String = operation.label,
    ): OperationEntry = recordEntry(operation, UndoStrategy.Irreversible(reason), targetLabel)

    private suspend fun recordEntry(
        operation: GitOperationKind,
        strategy: UndoStrategy,
        targetLabel: String,
    ): OperationEntry {
        val entry = OperationEntry(
            operation = operation,
            strategy = strategy,
            baseline = refGateway.currentBaseline(),
            targetLabel = targetLabel,
            recordedAt = clock.instant(),
        )
        undoStack.record(entry)
        return entry
    }
}
