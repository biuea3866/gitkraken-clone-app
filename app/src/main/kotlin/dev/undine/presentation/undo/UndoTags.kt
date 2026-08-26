package dev.undine.presentation.undo

/** Undo 영역의 테스트·접근성 식별자. 실제 앱 조립은 UND-51이 맡는다. */
object UndoTags {
    const val ROOT: String = "undo.root"
    const val BUTTON: String = "undo.button"
    const val TOOLTIP: String = "undo.tooltip"
    const val DISCARD: String = "undo.discard"
    const val LOAD_FAILURE: String = "undo.loadFailure"
    const val HISTORY: String = "undo.history"
}
