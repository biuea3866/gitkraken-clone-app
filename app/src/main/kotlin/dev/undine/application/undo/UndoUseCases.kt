package dev.undine.application.undo

import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStack

/**
 * 되돌릴 다음 한 항목의 상태를 읽는다 — 대상과, 지금 못 되돌린다면 그 사유.
 *
 * 화면이 [UndoService]·[UndoStack]·Gateway 를 직접 잡지 않게 하는 얇은 층이다 (레이어 규칙 3).
 * 판단은 [UndoService.preview] 가 하고 여기서는 호출만 한다.
 */
class PeekUndoTargetUseCase(private val undoService: UndoService) {

    suspend fun execute(): UndoTarget = undoService.preview()
}

/**
 * 이 세션의 실행 이력을 최신 우선으로 읽는다. 화면이 그대로 나열할 수 있는 순서다.
 *
 * 이력은 메모리 스택이라 저장소를 건드리지 않는다 — 그래서 suspend 가 아니다.
 */
class LoadUndoHistoryUseCase(private val undoStack: UndoStack) {

    fun execute(): List<OperationEntry> = undoStack.history()
}

/**
 * 스택 최상단 **한 항목만** 되돌린다.
 *
 * 특정 이력 행을 골라 되돌리거나 어느 지점까지 일괄로 되돌리는 경로는 만들지 않는다 — 중간 단계를
 * 건너뛴 되돌리기는 예측이 어렵고, Git 에서 그건 되돌리기가 아니라 새로운 사고다.
 *
 * 거부는 예외가 아니라 [UndoOutcome.Refused] 결과로 올라온다 — 화면이 성공으로 표시하지 않게.
 */
class UndoLastOperationUseCase(private val undoService: UndoService) {

    suspend fun execute(): UndoOutcome = undoService.undo()
}
