package dev.undine.application.undo

import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome

/**
 * Undo 버튼이 **누르기 전에** 알아야 하는 것 — 무엇을 되돌리는지, 못 한다면 왜인지.
 *
 * 세 상태를 sealed 로 닫아 두면 화면이 "비어 있음" 과 "막혀 있음" 을 한 nullable 로 뭉개
 * 사유를 빠뜨릴 수 없다. 실행 결과가 아니라 **실행 전 판단**이라 저장소를 바꾸지 않는다.
 */
sealed interface UndoTarget {

    /** 이 세션에서 기록한 연산이 없다. */
    data object None : UndoTarget

    /** 지금 되돌릴 수 있다. */
    data class Undoable(val entry: OperationEntry) : UndoTarget

    /** 최상단 기록은 있지만 지금은 되돌릴 수 없다. [refusal] 이 그 사유다. */
    data class Blocked(val entry: OperationEntry, val refusal: UndoOutcome.Refused) : UndoTarget
}
