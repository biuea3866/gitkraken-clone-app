package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "diff"

/**
 * `diff.*` 키 정의. 뷰어가 노출하는 문구는 **계산하지 않은 사유 안내**와 hunk·모드 조작 라벨뿐이다 —
 * diff 본문과 hunk 헤더(`@@ ... @@`)는 Git 이 만든 값이라 번역 대상이 아니다.
 */
object DiffKeys {
    val binaryNotice = StringKey("$NAMESPACE.notice.binary")
    val binaryDescription = StringKey("$NAMESPACE.notice.binaryDescription")
    val tooLargeNotice = StringKey("$NAMESPACE.notice.tooLarge")
    val tooLargeDescription = StringKey("$NAMESPACE.notice.tooLargeDescription")
    val noChangesNotice = StringKey("$NAMESPACE.notice.noChanges")
    val stageHunk = StringKey("$NAMESPACE.hunk.stage")
    val unifiedViewMode = StringKey("$NAMESPACE.viewMode.unified")
    val splitViewMode = StringKey("$NAMESPACE.viewMode.split")

    /** 번역 누락 검증이 키를 하나씩 나열하지 않도록 전수 목록을 노출한다. */
    val all: List<StringKey> = listOf(
        binaryNotice,
        binaryDescription,
        tooLargeNotice,
        tooLargeDescription,
        noChangesNotice,
        stageHunk,
        unifiedViewMode,
        splitViewMode,
    )
}

/**
 * diff 문구 접근자. `strings.diff.binaryNotice` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3).
 */
@JvmInline
value class DiffStrings internal constructor(private val strings: Strings) {
    val binaryNotice: String get() = strings.text(DiffKeys.binaryNotice)
    val binaryDescription: String get() = strings.text(DiffKeys.binaryDescription)
    val tooLargeNotice: String get() = strings.text(DiffKeys.tooLargeNotice)
    val tooLargeDescription: String get() = strings.text(DiffKeys.tooLargeDescription)
    val noChangesNotice: String get() = strings.text(DiffKeys.noChangesNotice)
    val stageHunk: String get() = strings.text(DiffKeys.stageHunk)
    val unifiedViewMode: String get() = strings.text(DiffKeys.unifiedViewMode)
    val splitViewMode: String get() = strings.text(DiffKeys.splitViewMode)
}

/** diff 문구 네임스페이스 진입점. */
val Strings.diff: DiffStrings get() = DiffStrings(this)

internal val diffTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        DiffKeys.binaryNotice to "이진 파일이라 diff 를 계산하지 않았습니다",
        DiffKeys.binaryDescription to "변경 여부는 파일 목록에서 확인하세요.",
        DiffKeys.tooLargeNotice to "파일이 너무 커서 diff 를 계산하지 않았습니다",
        DiffKeys.tooLargeDescription to "임계치를 넘는 파일은 화면이 멈추지 않도록 건너뜁니다.",
        DiffKeys.noChangesNotice to "이 파일에는 표시할 변경이 없습니다",
        DiffKeys.stageHunk to "이 hunk 스테이징",
        DiffKeys.unifiedViewMode to "통합 보기",
        DiffKeys.splitViewMode to "분할 보기",
    ),
    Locale.ENGLISH to mapOf(
        DiffKeys.binaryNotice to "Diff not computed — binary file",
        DiffKeys.binaryDescription to "Check the file list to see whether it changed.",
        DiffKeys.tooLargeNotice to "Diff not computed — file is too large",
        DiffKeys.tooLargeDescription to "Files over the threshold are skipped to keep the view responsive.",
        DiffKeys.noChangesNotice to "This file has no changes to show",
        DiffKeys.stageHunk to "Stage this hunk",
        DiffKeys.unifiedViewMode to "Unified",
        DiffKeys.splitViewMode to "Split",
    ),
)
