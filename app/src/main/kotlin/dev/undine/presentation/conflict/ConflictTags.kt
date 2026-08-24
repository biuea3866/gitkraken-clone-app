package dev.undine.presentation.conflict

/** 화면 테스트가 에디터 요소를 집는 태그. 요소를 없애거나 옮기면 이 값도 함께 바뀐다. */
object ConflictTags {
    const val ROOT = "conflict.root"
    const val FILE_LIST = "conflict.files"
    const val EMPTY = "conflict.empty"
    const val PROGRESS = "conflict.progress"
    const val OURS_PANE = "conflict.pane.ours"
    const val BASE_PANE = "conflict.pane.base"
    const val THEIRS_PANE = "conflict.pane.theirs"
    const val RESULT_PANE = "conflict.pane.result"
    const val RESULT_EDITOR = "conflict.resultEditor"
    const val TAKE_OURS = "conflict.takeOurs"
    const val TAKE_THEIRS = "conflict.takeTheirs"
    const val TAKE_BOTH = "conflict.takeBoth"
    const val SAVE = "conflict.save"
    const val CONTINUE = "conflict.continue"
    const val BINARY_NOTICE = "conflict.binaryNotice"
    const val MARKERS_REMAIN = "conflict.markersRemain"
    const val ABORT_NOTICE = "conflict.abortNotice"
    const val ABORT = "conflict.abort"
    const val ABORT_DIALOG = "conflict.abortDialog"
    const val ABORT_PATHS = "conflict.abortPaths"
    const val ABORT_ACCEPT = "conflict.abortAccept"
    const val ABORT_CANCEL = "conflict.abortCancel"
    const val ABORT_STALE = "conflict.abortStale"
    const val FAILURE = "conflict.failure"

    fun fileRow(path: String): String = "conflict.file.$path"

    fun regionTab(index: Int): String = "conflict.region.$index"
}
