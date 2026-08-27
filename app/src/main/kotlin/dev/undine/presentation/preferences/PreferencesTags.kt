package dev.undine.presentation.preferences

/** 화면 요소를 가리키는 테스트 태그. 문구가 바뀌어도 가리키는 대상이 흔들리지 않게 한다. */
object PreferencesTags {
    const val ROOT: String = "preferences.root"
    const val TAB_BAR: String = "preferences.tabBar"
    const val TAB: String = "preferences.tab"
    const val CONTENT: String = "preferences.content"
    const val ROW: String = "preferences.row"
    const val ROW_SOURCE: String = "preferences.row.source"
    const val ROW_RESTORE_DEFAULT: String = "preferences.row.restoreDefault"
    const val RESET_ALL: String = "preferences.resetAll"
    const val RESET_CONFIRMATION: String = "preferences.resetConfirmation"
    const val LOAD_FAILURE: String = "preferences.loadFailure"
    const val SAVE_FAILURE: String = "preferences.saveFailure"
}
