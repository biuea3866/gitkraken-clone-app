package dev.undine.presentation.preferences

/**
 * 계정 탭 요소를 가리키는 테스트 태그. 문구가 바뀌어도 가리키는 대상이 흔들리지 않게 한다.
 *
 * 공용 [PreferencesTags] 를 늘리지 않고 탭 자기 파일에 둔다 — 같은 wave 의 여섯 탭이 그 파일을
 * 동시에 고치면 머지가 충돌한다.
 */
object AccountPreferencesTags {
    const val ROOT: String = "preferences.account"
    const val PROFILE: String = "preferences.account.profile"
    const val PROFILE_EMPTY: String = "preferences.account.profileEmpty"
    const val PROFILE_ADD: String = "preferences.account.profileAdd"
    const val PROFILE_EDIT: String = "preferences.account.profileEdit"
    const val PROFILE_DELETE: String = "preferences.account.profileDelete"
    const val DELETE_CONFIRMATION: String = "preferences.account.deleteConfirmation"
    const val DELETE_CONFIRM: String = "preferences.account.deleteConfirm"
    const val DELETE_CANCEL: String = "preferences.account.deleteCancel"
    const val EDITOR: String = "preferences.account.editor"
    const val EDITOR_NAME: String = "preferences.account.editor.name"
    const val EDITOR_EMAIL: String = "preferences.account.editor.email"
    const val EDITOR_SIGNING_KEY: String = "preferences.account.editor.signingKey"
    const val EDITOR_SUBMIT: String = "preferences.account.editor.submit"
    const val EDITOR_CANCEL: String = "preferences.account.editor.cancel"
    const val MAPPING: String = "preferences.account.mapping"
    const val MAPPING_ASSIGN: String = "preferences.account.mapping.assign"
    const val MAPPING_CLEAR: String = "preferences.account.mapping.clear"
    const val LOAD_FAILURE: String = "preferences.account.loadFailure"
    const val SAVE_FAILURE: String = "preferences.account.saveFailure"
}
