package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `patch.*` 네임스페이스 — 패치를 만들고 적용하는 화면의 문구.
 *
 * 모양은 [CommonStrings] 가 정본이다: [PATCH_NAMESPACE] 로 키를 만들고, 번역 맵을 로케일별로 채우고,
 * `Strings.patch` 확장 프로퍼티로 노출한다. 화면은 문자열을 코드에 박지 않고 여기서만 읽는다.
 */
internal const val PATCH_NAMESPACE: String = "patch"

/** Patch 화면의 `patch.*` 키. */
@Suppress("TooManyFunctions") // 키 정의 object 다 — 화면 문구 수만큼 늘어나는 것이 정상이다.
object PatchKeys {
    val title = StringKey("$PATCH_NAMESPACE.title")

    val create = StringKey("$PATCH_NAMESPACE.create")
    val sourceWorkingTree = StringKey("$PATCH_NAMESPACE.sourceWorkingTree")
    val sourceCommits = StringKey("$PATCH_NAMESPACE.sourceCommits")
    val scopeStaged = StringKey("$PATCH_NAMESPACE.scopeStaged")
    val scopeUnstaged = StringKey("$PATCH_NAMESPACE.scopeUnstaged")
    val scopeAll = StringKey("$PATCH_NAMESPACE.scopeAll")
    val formatCombined = StringKey("$PATCH_NAMESPACE.formatCombined")
    val formatPerCommit = StringKey("$PATCH_NAMESPACE.formatPerCommit")
    val commitsLoading = StringKey("$PATCH_NAMESPACE.commitsLoading")
    val commitsEmpty = StringKey("$PATCH_NAMESPACE.commitsEmpty")
    val commitsFailed = StringKey("$PATCH_NAMESPACE.commitsFailed")
    val commitSelectionHint = StringKey("$PATCH_NAMESPACE.commitSelectionHint")
    val commitSelectionEmpty = StringKey("$PATCH_NAMESPACE.commitSelectionEmpty")
    val prepare = StringKey("$PATCH_NAMESPACE.prepare")
    val preparing = StringKey("$PATCH_NAMESPACE.preparing")
    val prepareFailed = StringKey("$PATCH_NAMESPACE.prepareFailed")
    val includedFiles = StringKey("$PATCH_NAMESPACE.includedFiles")
    val totalSize = StringKey("$PATCH_NAMESPACE.totalSize")
    val includedFilesEmpty = StringKey("$PATCH_NAMESPACE.includedFilesEmpty")
    val save = StringKey("$PATCH_NAMESPACE.save")
    val saving = StringKey("$PATCH_NAMESPACE.saving")
    val saved = StringKey("$PATCH_NAMESPACE.saved")
    val saveFailed = StringKey("$PATCH_NAMESPACE.saveFailed")
    val saveRollbackFailed = StringKey("$PATCH_NAMESPACE.saveRollbackFailed")
    val saveTemporaryLeft = StringKey("$PATCH_NAMESPACE.saveTemporaryLeft")
    val overwriteWarning = StringKey("$PATCH_NAMESPACE.overwriteWarning")
    val overwriteConfirm = StringKey("$PATCH_NAMESPACE.overwriteConfirm")
    val overwriteCancel = StringKey("$PATCH_NAMESPACE.overwriteCancel")

    /** OS 파일 대화상자 제목. 창을 여는 것은 배선이지만 문구는 다른 화면 문구와 같은 카탈로그에서 읽는다. */
    val openDialogTitle = StringKey("$PATCH_NAMESPACE.openDialogTitle")
    val saveDialogTitle = StringKey("$PATCH_NAMESPACE.saveDialogTitle")
    val directoryDialogTitle = StringKey("$PATCH_NAMESPACE.directoryDialogTitle")

    val apply = StringKey("$PATCH_NAMESPACE.apply")
    val chooseFile = StringKey("$PATCH_NAMESPACE.chooseFile")
    val dropHint = StringKey("$PATCH_NAMESPACE.dropHint")
    val dropRejectedEmpty = StringKey("$PATCH_NAMESPACE.dropRejectedEmpty")
    val dropRejectedMultiple = StringKey("$PATCH_NAMESPACE.dropRejectedMultiple")
    val readingPatch = StringKey("$PATCH_NAMESPACE.readingPatch")
    val readFailed = StringKey("$PATCH_NAMESPACE.readFailed")
    val selectedPatch = StringKey("$PATCH_NAMESPACE.selectedPatch")
    val dryRunRunning = StringKey("$PATCH_NAMESPACE.dryRunRunning")
    val dryRunFailed = StringKey("$PATCH_NAMESPACE.dryRunFailed")
    val changedFiles = StringKey("$PATCH_NAMESPACE.changedFiles")
    val conflicted = StringKey("$PATCH_NAMESPACE.conflicted")
    val unsupportedThreeWay = StringKey("$PATCH_NAMESPACE.unsupportedThreeWay")
    val unsupportedReverse = StringKey("$PATCH_NAMESPACE.unsupportedReverse")
    val blockedStateViolation = StringKey("$PATCH_NAMESPACE.blockedStateViolation")
    val noChange = StringKey("$PATCH_NAMESPACE.noChange")
    val applied = StringKey("$PATCH_NAMESPACE.applied")
    val applying = StringKey("$PATCH_NAMESPACE.applying")

    val modeWorkingTree = StringKey("$PATCH_NAMESPACE.modeWorkingTree")
    val modeIndex = StringKey("$PATCH_NAMESPACE.modeIndex")
    val modeCommit = StringKey("$PATCH_NAMESPACE.modeCommit")
    val commitAuthorName = StringKey("$PATCH_NAMESPACE.commitAuthorName")
    val commitAuthorEmail = StringKey("$PATCH_NAMESPACE.commitAuthorEmail")
    val commitMessage = StringKey("$PATCH_NAMESPACE.commitMessage")
    val fromPatchMetadata = StringKey("$PATCH_NAMESPACE.fromPatchMetadata")
    val commitInputRequired = StringKey("$PATCH_NAMESPACE.commitInputRequired")

    val preview = StringKey("$PATCH_NAMESPACE.preview")
    val previewUnified = StringKey("$PATCH_NAMESPACE.previewUnified")
    val previewSplit = StringKey("$PATCH_NAMESPACE.previewSplit")
    val previewBinary = StringKey("$PATCH_NAMESPACE.previewBinary")
    val previewTooLarge = StringKey("$PATCH_NAMESPACE.previewTooLarge")
    val previewNoChange = StringKey("$PATCH_NAMESPACE.previewNoChange")
    val previewStillApplicable = StringKey("$PATCH_NAMESPACE.previewStillApplicable")

    /**
     * 적용·저장이 도는 동안 화면을 떠나거나 **저장소를 바꿀** 수 없는 사유 (결정 C3·C6).
     *
     * 두 경로가 같은 판정을 보므로 문구도 하나다 — 나누면 어느 쪽이 진짜 사유인지 사용자가 가려야 한다.
     * 차단 표면은 기존 것을 쓰고 문구만 여기서 온다.
     */
    val exitBlocked = StringKey("$PATCH_NAMESPACE.exitBlocked")
}

/** Patch 화면 문구 접근자. */
@JvmInline
@Suppress("TooManyFunctions") // 접근자 묶음이다 — 키 하나당 하나씩 대응한다.
value class PatchStrings internal constructor(private val strings: Strings) {
    val title: String get() = strings.text(PatchKeys.title)

    val create: String get() = strings.text(PatchKeys.create)
    val sourceWorkingTree: String get() = strings.text(PatchKeys.sourceWorkingTree)
    val sourceCommits: String get() = strings.text(PatchKeys.sourceCommits)
    val scopeStaged: String get() = strings.text(PatchKeys.scopeStaged)
    val scopeUnstaged: String get() = strings.text(PatchKeys.scopeUnstaged)
    val scopeAll: String get() = strings.text(PatchKeys.scopeAll)
    val formatCombined: String get() = strings.text(PatchKeys.formatCombined)
    val formatPerCommit: String get() = strings.text(PatchKeys.formatPerCommit)
    val commitsLoading: String get() = strings.text(PatchKeys.commitsLoading)
    val commitsEmpty: String get() = strings.text(PatchKeys.commitsEmpty)
    val commitsFailed: String get() = strings.text(PatchKeys.commitsFailed)
    val commitSelectionHint: String get() = strings.text(PatchKeys.commitSelectionHint)
    val commitSelectionEmpty: String get() = strings.text(PatchKeys.commitSelectionEmpty)
    val prepare: String get() = strings.text(PatchKeys.prepare)
    val preparing: String get() = strings.text(PatchKeys.preparing)
    val prepareFailed: String get() = strings.text(PatchKeys.prepareFailed)
    val includedFiles: String get() = strings.text(PatchKeys.includedFiles)
    fun totalSize(bytes: Int): String = strings.text(PatchKeys.totalSize, bytes)
    val includedFilesEmpty: String get() = strings.text(PatchKeys.includedFilesEmpty)
    val save: String get() = strings.text(PatchKeys.save)
    val saving: String get() = strings.text(PatchKeys.saving)
    fun saved(target: String): String = strings.text(PatchKeys.saved, target)
    val saveFailed: String get() = strings.text(PatchKeys.saveFailed)
    val saveRollbackFailed: String get() = strings.text(PatchKeys.saveRollbackFailed)
    fun saveTemporaryLeft(paths: String): String = strings.text(PatchKeys.saveTemporaryLeft, paths)
    fun overwriteWarning(names: String): String = strings.text(PatchKeys.overwriteWarning, names)
    val overwriteConfirm: String get() = strings.text(PatchKeys.overwriteConfirm)
    val overwriteCancel: String get() = strings.text(PatchKeys.overwriteCancel)

    val openDialogTitle: String get() = strings.text(PatchKeys.openDialogTitle)
    val saveDialogTitle: String get() = strings.text(PatchKeys.saveDialogTitle)
    val directoryDialogTitle: String get() = strings.text(PatchKeys.directoryDialogTitle)

    val apply: String get() = strings.text(PatchKeys.apply)
    val chooseFile: String get() = strings.text(PatchKeys.chooseFile)
    val dropHint: String get() = strings.text(PatchKeys.dropHint)
    val dropRejectedEmpty: String get() = strings.text(PatchKeys.dropRejectedEmpty)
    val dropRejectedMultiple: String get() = strings.text(PatchKeys.dropRejectedMultiple)
    val readingPatch: String get() = strings.text(PatchKeys.readingPatch)
    val readFailed: String get() = strings.text(PatchKeys.readFailed)
    fun selectedPatch(name: String): String = strings.text(PatchKeys.selectedPatch, name)
    val dryRunRunning: String get() = strings.text(PatchKeys.dryRunRunning)
    val dryRunFailed: String get() = strings.text(PatchKeys.dryRunFailed)
    val changedFiles: String get() = strings.text(PatchKeys.changedFiles)
    val conflicted: String get() = strings.text(PatchKeys.conflicted)
    val unsupportedThreeWay: String get() = strings.text(PatchKeys.unsupportedThreeWay)
    val unsupportedReverse: String get() = strings.text(PatchKeys.unsupportedReverse)
    fun blockedStateViolation(detail: String): String = strings.text(PatchKeys.blockedStateViolation, detail)
    val noChange: String get() = strings.text(PatchKeys.noChange)
    val applied: String get() = strings.text(PatchKeys.applied)
    val applying: String get() = strings.text(PatchKeys.applying)

    val modeWorkingTree: String get() = strings.text(PatchKeys.modeWorkingTree)
    val modeIndex: String get() = strings.text(PatchKeys.modeIndex)
    val modeCommit: String get() = strings.text(PatchKeys.modeCommit)
    val commitAuthorName: String get() = strings.text(PatchKeys.commitAuthorName)
    val commitAuthorEmail: String get() = strings.text(PatchKeys.commitAuthorEmail)
    val commitMessage: String get() = strings.text(PatchKeys.commitMessage)
    val fromPatchMetadata: String get() = strings.text(PatchKeys.fromPatchMetadata)
    val commitInputRequired: String get() = strings.text(PatchKeys.commitInputRequired)

    val preview: String get() = strings.text(PatchKeys.preview)
    val previewUnified: String get() = strings.text(PatchKeys.previewUnified)
    val previewSplit: String get() = strings.text(PatchKeys.previewSplit)
    val previewBinary: String get() = strings.text(PatchKeys.previewBinary)
    val previewTooLarge: String get() = strings.text(PatchKeys.previewTooLarge)
    val previewNoChange: String get() = strings.text(PatchKeys.previewNoChange)
    val previewStillApplicable: String get() = strings.text(PatchKeys.previewStillApplicable)

    val exitBlocked: String get() = strings.text(PatchKeys.exitBlocked)
}

/** Patch 문구 네임스페이스 진입점. */
val Strings.patch: PatchStrings get() = PatchStrings(this)

internal val patchTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        PatchKeys.title to "패치",
        PatchKeys.create to "패치 만들기",
        PatchKeys.sourceWorkingTree to "현재 변경",
        PatchKeys.sourceCommits to "커밋 선택",
        PatchKeys.scopeStaged to "스테이징한 변경만",
        PatchKeys.scopeUnstaged to "스테이징하지 않은 변경만",
        PatchKeys.scopeAll to "전체 변경",
        PatchKeys.formatCombined to "단일 통합 패치",
        PatchKeys.formatPerCommit to "커밋당 파일",
        PatchKeys.commitsLoading to "최근 커밋을 읽는 중…",
        PatchKeys.commitsEmpty to "표시할 커밋이 없습니다.",
        PatchKeys.commitsFailed to "최근 커밋을 읽지 못했습니다.",
        PatchKeys.commitSelectionHint to "커밋을 누르면 시작점이 되고, 다른 커밋을 누르면 그 사이 구간이 선택됩니다.",
        PatchKeys.commitSelectionEmpty to "커밋을 골라야 패치를 만들 수 있습니다.",
        PatchKeys.prepare to "포함 내용 확인",
        PatchKeys.preparing to "패치를 만드는 중…",
        PatchKeys.prepareFailed to "패치를 만들지 못했습니다.",
        PatchKeys.includedFiles to "포함될 파일",
        PatchKeys.totalSize to "총 크기: {0}바이트",
        PatchKeys.includedFilesEmpty to "포함될 변경이 없습니다.",
        PatchKeys.save to "저장",
        PatchKeys.saving to "패치를 저장하는 중…",
        PatchKeys.saved to "저장했습니다: {0}",
        PatchKeys.saveFailed to "패치를 저장하지 못했습니다.",
        PatchKeys.saveRollbackFailed to "저장을 되돌리는 것도 실패해, 대상 폴더에 반쯤 쓰인 파일이 남았을 수 있습니다.",
        PatchKeys.saveTemporaryLeft to "임시 파일을 지우지 못했습니다. 직접 지워 주세요: {0}",
        PatchKeys.overwriteWarning to "같은 이름의 파일이 이미 있습니다: {0}",
        PatchKeys.overwriteConfirm to "덮어쓰고 저장",
        PatchKeys.overwriteCancel to "저장 취소",
        PatchKeys.openDialogTitle to "적용할 패치 파일 선택",
        PatchKeys.saveDialogTitle to "패치 저장",
        PatchKeys.directoryDialogTitle to "패치를 저장할 폴더 선택",
        PatchKeys.apply to "패치 적용",
        PatchKeys.chooseFile to "패치 파일 선택",
        PatchKeys.dropHint to "패치 파일 하나를 여기에 끌어다 놓을 수 있습니다.",
        PatchKeys.dropRejectedEmpty to "끌어다 놓은 것에서 파일을 찾지 못했습니다.",
        PatchKeys.dropRejectedMultiple to "패치 파일은 하나만 받습니다. 여러 개를 놓으면 무엇을 적용할지 정할 수 없습니다.",
        PatchKeys.readingPatch to "패치 파일을 읽는 중…",
        PatchKeys.readFailed to "패치 파일을 읽지 못했습니다.",
        PatchKeys.selectedPatch to "선택한 패치: {0}",
        PatchKeys.dryRunRunning to "적용 가능한지 확인하는 중…",
        PatchKeys.dryRunFailed to "적용 가능 여부를 확인하지 못했습니다.",
        PatchKeys.changedFiles to "변경될 파일",
        PatchKeys.conflicted to "다음 경로에 붙지 않아 적용할 수 없습니다.",
        PatchKeys.unsupportedThreeWay to "컨텍스트가 어긋나 3-way 적용이 필요한 패치라 적용할 수 없습니다.",
        PatchKeys.unsupportedReverse to "copy·rename·mode 변경·binary 가 섞여 있어 적용할 수 없습니다.",
        PatchKeys.blockedStateViolation to "적용을 거부했습니다: {0}",
        PatchKeys.noChange to "적용할 변경이 없습니다.",
        PatchKeys.applied to "적용한 파일",
        PatchKeys.applying to "패치를 적용하는 중…",
        PatchKeys.modeWorkingTree to "워킹트리만",
        PatchKeys.modeIndex to "인덱스까지",
        PatchKeys.modeCommit to "커밋까지",
        PatchKeys.commitAuthorName to "작성자 이름",
        PatchKeys.commitAuthorEmail to "작성자 메일",
        PatchKeys.commitMessage to "커밋 메시지",
        PatchKeys.fromPatchMetadata to "패치에서 가져옴",
        PatchKeys.commitInputRequired to "패치에 작성자·메시지가 없어 직접 입력해야 커밋할 수 있습니다.",
        PatchKeys.preview to "패치 미리보기",
        PatchKeys.previewUnified to "통합 보기",
        PatchKeys.previewSplit to "분할 보기",
        PatchKeys.previewBinary to "binary 패치라 미리보기를 만들 수 없습니다.",
        PatchKeys.previewTooLarge to "패치가 너무 커서 미리보기를 만들지 않았습니다.",
        PatchKeys.previewNoChange to "미리볼 변경이 없습니다.",
        PatchKeys.previewStillApplicable to "미리보기를 못 만들었을 뿐이며 적용은 그대로 할 수 있습니다.",
        PatchKeys.exitBlocked to
            "패치를 적용하거나 저장하는 중에는 이 화면을 떠나거나 저장소를 바꿀 수 없습니다. 끝나면 바로 풀립니다.",
    ),
    Locale.ENGLISH to mapOf(
        PatchKeys.title to "Patch",
        PatchKeys.create to "Create patch",
        PatchKeys.sourceWorkingTree to "Current changes",
        PatchKeys.sourceCommits to "Select commits",
        PatchKeys.scopeStaged to "Staged changes only",
        PatchKeys.scopeUnstaged to "Unstaged changes only",
        PatchKeys.scopeAll to "All changes",
        PatchKeys.formatCombined to "Single combined patch",
        PatchKeys.formatPerCommit to "One file per commit",
        PatchKeys.commitsLoading to "Loading recent commits…",
        PatchKeys.commitsEmpty to "There are no commits to show.",
        PatchKeys.commitsFailed to "Could not load recent commits.",
        PatchKeys.commitSelectionHint to
            "Click a commit to anchor the range, then click another to select everything in between.",
        PatchKeys.commitSelectionEmpty to "Select at least one commit to create a patch.",
        PatchKeys.prepare to "Show what is included",
        PatchKeys.preparing to "Creating the patch…",
        PatchKeys.prepareFailed to "Could not create the patch.",
        PatchKeys.includedFiles to "Included files",
        PatchKeys.totalSize to "Total size: {0} bytes",
        PatchKeys.includedFilesEmpty to "No changes would be included.",
        PatchKeys.save to "Save",
        PatchKeys.saving to "Saving the patch…",
        PatchKeys.saved to "Saved to {0}",
        PatchKeys.saveFailed to "Could not save the patch.",
        PatchKeys.saveRollbackFailed to
            "Undoing the save also failed, so half-written files may remain in the target folder.",
        PatchKeys.saveTemporaryLeft to "Temporary files could not be removed. Please delete them yourself: {0}",
        PatchKeys.overwriteWarning to "Files with these names already exist: {0}",
        PatchKeys.overwriteConfirm to "Overwrite and save",
        PatchKeys.overwriteCancel to "Cancel save",
        PatchKeys.openDialogTitle to "Choose the patch file to apply",
        PatchKeys.saveDialogTitle to "Save the patch",
        PatchKeys.directoryDialogTitle to "Choose a folder for the patch files",
        PatchKeys.apply to "Apply patch",
        PatchKeys.chooseFile to "Choose patch file",
        PatchKeys.dropHint to "You can drop a single patch file here.",
        PatchKeys.dropRejectedEmpty to "No file was found in what you dropped.",
        PatchKeys.dropRejectedMultiple to
            "Only one patch file is accepted; with several dropped there is no way to tell which to apply.",
        PatchKeys.readingPatch to "Reading the patch file…",
        PatchKeys.readFailed to "Could not read the patch file.",
        PatchKeys.selectedPatch to "Selected patch: {0}",
        PatchKeys.dryRunRunning to "Checking whether it applies…",
        PatchKeys.dryRunFailed to "Could not check whether the patch applies.",
        PatchKeys.changedFiles to "Files that would change",
        PatchKeys.conflicted to "The patch does not apply to these paths, so it cannot be applied.",
        PatchKeys.unsupportedThreeWay to
            "The context has drifted and a three-way apply would be required, so it cannot be applied.",
        PatchKeys.unsupportedReverse to
            "The patch mixes copy, rename, mode changes, or binary content, so it cannot be applied.",
        PatchKeys.blockedStateViolation to "The patch was refused: {0}",
        PatchKeys.noChange to "There is nothing to apply.",
        PatchKeys.applied to "Applied files",
        PatchKeys.applying to "Applying the patch…",
        PatchKeys.modeWorkingTree to "Working tree only",
        PatchKeys.modeIndex to "Working tree and index",
        PatchKeys.modeCommit to "Create a commit",
        PatchKeys.commitAuthorName to "Author name",
        PatchKeys.commitAuthorEmail to "Author email",
        PatchKeys.commitMessage to "Commit message",
        PatchKeys.fromPatchMetadata to "Taken from the patch",
        PatchKeys.commitInputRequired to
            "The patch carries no author or message, so you must type them before committing.",
        PatchKeys.preview to "Patch preview",
        PatchKeys.previewUnified to "Unified view",
        PatchKeys.previewSplit to "Split view",
        PatchKeys.previewBinary to "This is a binary patch, so no preview can be built.",
        PatchKeys.previewTooLarge to "The patch is too large, so no preview was built.",
        PatchKeys.previewNoChange to "There is nothing to preview.",
        PatchKeys.previewStillApplicable to "Only the preview is missing; the patch can still be applied.",
        PatchKeys.exitBlocked to
            "You cannot leave this screen or switch repositories while a patch is being applied or saved; " +
                "it unlocks as soon as that finishes.",
    ),
)
