package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "conflict"

/** `conflict.*` 키 정의. */
object ConflictKeys {
    val filesTitle = StringKey("$NAMESPACE.filesTitle")
    val progress = StringKey("$NAMESPACE.progress")
    val resolvedMark = StringKey("$NAMESPACE.resolvedMark")
    val oursPane = StringKey("$NAMESPACE.pane.ours")
    val basePane = StringKey("$NAMESPACE.pane.base")
    val theirsPane = StringKey("$NAMESPACE.pane.theirs")
    val resultPane = StringKey("$NAMESPACE.pane.result")
    val takeOurs = StringKey("$NAMESPACE.action.takeOurs")
    val takeTheirs = StringKey("$NAMESPACE.action.takeTheirs")
    val takeBoth = StringKey("$NAMESPACE.action.takeBoth")
    val editRegion = StringKey("$NAMESPACE.action.edit")
    val save = StringKey("$NAMESPACE.save")
    val continueOperation = StringKey("$NAMESPACE.continue")
    val binaryNotice = StringKey("$NAMESPACE.binaryNotice")
    val emptyTitle = StringKey("$NAMESPACE.emptyTitle")
    val emptyDescription = StringKey("$NAMESPACE.emptyDescription")

    /** 표식 잔존 차단. 남은 위치를 함께 알려야 사용자가 찾아갈 수 있다. */
    val markersRemain = StringKey("$NAMESPACE.markersRemain")

    /** abort 안내와 확인. 사라질 경로와 복구 불가성을 보여 준 뒤에만 실행한다. */
    val abortNotice = StringKey("$NAMESPACE.abortNotice")
    val abort = StringKey("$NAMESPACE.abort")
    val abortConfirmTitle = StringKey("$NAMESPACE.abortConfirm.title")
    val abortConfirmPaths = StringKey("$NAMESPACE.abortConfirm.paths")
    val abortConfirmIrreversible = StringKey("$NAMESPACE.abortConfirm.irreversible")
    val abortConfirmAccept = StringKey("$NAMESPACE.abortConfirm.accept")
    val abortStale = StringKey("$NAMESPACE.abortStale")
}

/** 충돌 에디터 문구 접근자. */
@JvmInline
value class ConflictStrings internal constructor(private val strings: Strings) {
    val filesTitle: String get() = strings.text(ConflictKeys.filesTitle)
    val resolvedMark: String get() = strings.text(ConflictKeys.resolvedMark)
    val oursPane: String get() = strings.text(ConflictKeys.oursPane)
    val basePane: String get() = strings.text(ConflictKeys.basePane)
    val theirsPane: String get() = strings.text(ConflictKeys.theirsPane)
    val resultPane: String get() = strings.text(ConflictKeys.resultPane)
    val takeOurs: String get() = strings.text(ConflictKeys.takeOurs)
    val takeTheirs: String get() = strings.text(ConflictKeys.takeTheirs)
    val takeBoth: String get() = strings.text(ConflictKeys.takeBoth)
    val editRegion: String get() = strings.text(ConflictKeys.editRegion)
    val save: String get() = strings.text(ConflictKeys.save)
    val continueOperation: String get() = strings.text(ConflictKeys.continueOperation)
    val binaryNotice: String get() = strings.text(ConflictKeys.binaryNotice)
    val emptyTitle: String get() = strings.text(ConflictKeys.emptyTitle)
    val emptyDescription: String get() = strings.text(ConflictKeys.emptyDescription)
    val abortNotice: String get() = strings.text(ConflictKeys.abortNotice)
    val abort: String get() = strings.text(ConflictKeys.abort)
    val abortConfirmTitle: String get() = strings.text(ConflictKeys.abortConfirmTitle)
    val abortConfirmIrreversible: String get() = strings.text(ConflictKeys.abortConfirmIrreversible)
    val abortConfirmAccept: String get() = strings.text(ConflictKeys.abortConfirmAccept)
    val abortStale: String get() = strings.text(ConflictKeys.abortStale)

    fun progress(resolved: Int, total: Int): String =
        strings.text(ConflictKeys.progress, resolved.toString(), total.toString())

    fun markersRemain(lines: String): String = strings.text(ConflictKeys.markersRemain, lines)

    fun abortConfirmPaths(paths: String): String = strings.text(ConflictKeys.abortConfirmPaths, paths)
}

/** 충돌 문구 네임스페이스 진입점. */
val Strings.conflict: ConflictStrings get() = ConflictStrings(this)

internal val conflictTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        ConflictKeys.filesTitle to "충돌한 파일",
        ConflictKeys.progress to "{1}개 중 {0}개 해결",
        ConflictKeys.resolvedMark to "해결됨",
        ConflictKeys.oursPane to "ours (현재 브랜치)",
        ConflictKeys.basePane to "base (공통 조상)",
        ConflictKeys.theirsPane to "theirs (가져온 쪽)",
        ConflictKeys.resultPane to "결과",
        ConflictKeys.takeOurs to "ours 채택",
        ConflictKeys.takeTheirs to "theirs 채택",
        ConflictKeys.takeBoth to "둘 다",
        ConflictKeys.editRegion to "직접 편집",
        ConflictKeys.save to "저장",
        ConflictKeys.continueOperation to "계속",
        ConflictKeys.binaryNotice to "이진 파일은 합칠 수 없습니다. 한쪽을 고르세요.",
        ConflictKeys.emptyTitle to "충돌이 없습니다",
        ConflictKeys.emptyDescription to "해결할 충돌이 남아 있지 않습니다.",
        ConflictKeys.markersRemain to "충돌 표식이 남아 있어 저장하지 않았습니다 — {0}번째 줄",
        ConflictKeys.abortNotice to "언제든 중단하면 병합·리베이스 전 상태로 되돌립니다.",
        ConflictKeys.abort to "중단",
        ConflictKeys.abortConfirmTitle to "중단하면 아래 편집이 사라집니다",
        ConflictKeys.abortConfirmPaths to "사라질 경로: {0}",
        ConflictKeys.abortConfirmIrreversible to "워킹트리에 쓴 편집은 되돌릴 수 없습니다.",
        ConflictKeys.abortConfirmAccept to "중단하고 되돌리기",
        ConflictKeys.abortStale to "확인한 뒤 편집이 늘었습니다. 갱신된 목록을 다시 확인하세요.",
    ),
    Locale.ENGLISH to mapOf(
        ConflictKeys.filesTitle to "Conflicted files",
        ConflictKeys.progress to "{0} of {1} resolved",
        ConflictKeys.resolvedMark to "resolved",
        ConflictKeys.oursPane to "ours (current branch)",
        ConflictKeys.basePane to "base (common ancestor)",
        ConflictKeys.theirsPane to "theirs (incoming)",
        ConflictKeys.resultPane to "Result",
        ConflictKeys.takeOurs to "Take ours",
        ConflictKeys.takeTheirs to "Take theirs",
        ConflictKeys.takeBoth to "Take both",
        ConflictKeys.editRegion to "Edit",
        ConflictKeys.save to "Save",
        ConflictKeys.continueOperation to "Continue",
        ConflictKeys.binaryNotice to "Binary files cannot be merged. Pick one side.",
        ConflictKeys.emptyTitle to "No conflicts",
        ConflictKeys.emptyDescription to "There are no conflicts left to resolve.",
        ConflictKeys.markersRemain to "Conflict markers remain, so nothing was saved — line {0}",
        ConflictKeys.abortNotice to "You can abort at any time to restore the pre-merge state.",
        ConflictKeys.abort to "Abort",
        ConflictKeys.abortConfirmTitle to "Aborting discards the edits below",
        ConflictKeys.abortConfirmPaths to "Will be discarded: {0}",
        ConflictKeys.abortConfirmIrreversible to "Edits written to the working tree cannot be recovered.",
        ConflictKeys.abortConfirmAccept to "Abort and restore",
        ConflictKeys.abortStale to "More edits appeared after you confirmed. Review the updated list.",
    ),
)
