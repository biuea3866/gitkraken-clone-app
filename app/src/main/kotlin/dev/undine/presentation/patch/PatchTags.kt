package dev.undine.presentation.patch

/** 화면 테스트가 패치 화면의 각 부분을 집는 태그. 구조를 바꾸면 이 값도 함께 바뀐다. */
object PatchTags {
    const val ROOT = "patch.root"

    const val SOURCE_WORKING_TREE = "patch.source.workingTree"
    const val SOURCE_COMMITS = "patch.source.commits"
    const val FORMAT_COMBINED = "patch.format.combined"
    const val FORMAT_PER_COMMIT = "patch.format.perCommit"
    const val COMMITS = "patch.commits"
    const val PREPARE = "patch.prepare"
    const val INCLUDED_FILES = "patch.includedFiles"
    const val TOTAL_SIZE = "patch.totalSize"
    const val SAVE = "patch.save"
    const val SAVE_PROGRESS = "patch.save.progress"
    const val SAVE_TEMPORARY_LEFT = "patch.save.temporaryLeft"
    const val OVERWRITE_CONFIRM = "patch.overwrite.confirm"
    const val OVERWRITE_CANCEL = "patch.overwrite.cancel"

    const val CHOOSE_FILE = "patch.chooseFile"
    const val DROP_AREA = "patch.dropArea"
    const val READ_NOTICE = "patch.readNotice"
    const val DRY_RUN = "patch.dryRun"
    const val APPLY = "patch.apply"
    const val APPLY_RESULT = "patch.applyResult"
    const val COMMIT_AUTHOR_NAME = "patch.commit.authorName"
    const val COMMIT_AUTHOR_EMAIL = "patch.commit.authorEmail"
    const val COMMIT_MESSAGE = "patch.commit.message"
    const val COMMIT_METADATA = "patch.commit.metadata"

    const val PREVIEW = "patch.preview"
    const val PREVIEW_NOTICE = "patch.preview.notice"
    const val PREVIEW_UNIFIED = "patch.preview.unified"
    const val PREVIEW_SPLIT = "patch.preview.split"

    fun scope(name: String): String = "patch.scope.${name.lowercase()}"

    fun mode(name: String): String = "patch.mode.${name.lowercase()}"

    fun commitRow(index: Int): String = "patch.commit.$index"
}
