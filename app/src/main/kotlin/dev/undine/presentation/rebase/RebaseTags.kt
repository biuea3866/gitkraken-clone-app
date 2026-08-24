package dev.undine.presentation.rebase

/** 화면 테스트가 계획 편집기 요소를 집는 태그. 요소를 없애거나 옮기면 이 값도 함께 바뀐다. */
object RebaseTags {
    const val ROOT = "rebase.root"
    const val EMPTY = "rebase.empty"
    const val PLAN_LIST = "rebase.planList"
    const val HINT = "rebase.hint"
    const val APPLY = "rebase.apply"
    const val DISCARD = "rebase.discard"
    const val PREVIEW = "rebase.preview"
    const val VIOLATION = "rebase.violation"
    const val PUSHED_WARNING = "rebase.pushedWarning"
    const val STOPS_WARNING = "rebase.stopsWarning"
    const val PROGRESS = "rebase.progress"
    const val OUTCOME = "rebase.outcome"
    const val FAILURE = "rebase.failure"

    fun planRow(index: Int): String = "rebase.row.$index"

    fun moveUp(index: Int): String = "rebase.row.$index.up"

    fun moveDown(index: Int): String = "rebase.row.$index.down"

    fun action(index: Int, action: String): String = "rebase.row.$index.action.$action"

    fun rewordField(index: Int): String = "rebase.row.$index.reword"

    fun rowMessage(index: Int): String = "rebase.row.$index.message"

    fun pushedMark(index: Int): String = "rebase.row.$index.pushed"

    fun previewRow(index: Int): String = "rebase.preview.$index"

    fun previewNote(index: Int): String = "rebase.preview.$index.note"
}
