package dev.undine.presentation.toolbar

/** 화면 테스트가 툴바의 각 요소를 집는 태그. 요소를 없애거나 옮기면 이 값도 함께 바뀐다. */
object ToolbarTags {
    const val ROOT = "toolbar.root"
    const val FETCH = "toolbar.fetch"
    const val PULL = "toolbar.pull"
    const val PUSH = "toolbar.push"
    const val CANCEL = "toolbar.cancel"
    const val CANCELLING = "toolbar.cancelling"
    const val PROGRESS = "toolbar.progress"
    const val PHASE = "toolbar.phase"
    const val MESSAGE = "toolbar.message"
    const val NOTICE = "toolbar.notice"
    const val AHEAD_BEHIND = "toolbar.aheadBehind"
    const val MORE_ACTIONS = "toolbar.moreActions"
    const val FORCE_PUSH = "toolbar.forcePush"
    const val FORCE_PUSH_WARNING = "toolbar.forcePush.warning"
    const val FORCE_PUSH_CONFIRM = "toolbar.forcePush.confirm"
    const val FORCE_PUSH_DISMISS = "toolbar.forcePush.dismiss"
}
