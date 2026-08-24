package dev.undine.presentation.staging

/**
 * 화면 테스트가 패널 요소를 집는 태그. 요소를 없애거나 옮기면 이 값도 함께 바뀐다.
 *
 * 파일 행 태그는 **목록과 경로를 함께** 담는다 — 일부만 stage 한 파일은 두 목록에 동시에 나타나므로
 * 경로만으로는 어느 쪽 행인지 가릴 수 없다.
 */
object StagingTags {
    const val ROOT = "staging.root"
    const val STAGED_LIST = "staging.staged"
    const val UNSTAGED_LIST = "staging.unstaged"
    const val EMPTY = "staging.empty"
    const val MESSAGE = "staging.message"
    const val COMMIT = "staging.commit"
    const val BLOCKED_REASON = "staging.blockedReason"
    const val AMEND_TOGGLE = "staging.amendToggle"
    const val AMEND_DIALOG = "staging.amendDialog"
    const val AMEND_ACCEPT = "staging.amendAccept"
    const val AMEND_CANCEL = "staging.amendCancel"
    const val STAGE_BUTTON = "staging.stageButton"
    const val UNSTAGE_BUTTON = "staging.unstageButton"
    const val FAILURE = "staging.failure"
    const val SUBJECT_GUIDE = "staging.subjectGuide"

    fun fileRow(side: StagingSide, path: String): String = "staging.row.${side.name}.$path"
}
