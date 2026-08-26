package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `blame.*` 네임스페이스 — 라인별 작성자 뷰와 파일 변경 이력 화면의 문구.
 *
 * **아직 비어 있다.** UND-63 이 [builtInTranslations] 등록까지만 해 두고, 키 정의 object·접근자
 * value class·로케일별 번역은 UND-41(Blame · 파일 이력 화면)이 **이 파일 안에서만** 채운다.
 * 공통 파일(`BuiltInStrings.kt`)은 이미 등록돼 있으므로 건드리지 않는다 — 같은 wave 의 화면 7건이
 * 그 파일을 함께 고치면 머지 충돌이 난다.
 *
 * 채우는 모양은 [CommonStrings] 가 정본이다: [BLAME_NAMESPACE] 로 키를 만들고, 번역 맵을
 * 로케일별로 채우고, `Strings.blame` 확장 프로퍼티로 노출한다.
 *
 * 빈 맵은 병합에서 아무 키도 더하지 않으므로 등록만으로 카탈로그 동작이 달라지지 않는다.
 * 이 네임스페이스의 키를 지금 조회하면 다른 미등록 키와 똑같이 폴백한다.
 */
internal const val BLAME_NAMESPACE: String = "blame"

/** Blame 화면이 추가할 `blame.*` 키의 자리. */
object BlameKeys {
    val loading = StringKey("$BLAME_NAMESPACE.loading")
    val loadMore = StringKey("$BLAME_NAMESPACE.loadMore")
    val ignoreWhitespace = StringKey("$BLAME_NAMESPACE.ignoreWhitespace")
    val recurseBefore = StringKey("$BLAME_NAMESPACE.recurseBefore")
    val unsupported = StringKey("$BLAME_NAMESPACE.unsupported")
    val noLines = StringKey("$BLAME_NAMESPACE.noLines")
    val loadFailed = StringKey("$BLAME_NAMESPACE.loadFailed")
    val fileHistory = StringKey("$BLAME_NAMESPACE.fileHistory")
    val renamedFrom = StringKey("$BLAME_NAMESPACE.renamedFrom")
    val comparisonStart = StringKey("$BLAME_NAMESPACE.comparisonStart")
    val comparisonResult = StringKey("$BLAME_NAMESPACE.comparisonResult")
}

/** Blame 문구 접근자. UND-41 이 여기에 화면별 문자열을 추가한다. */
@JvmInline
value class BlameStrings internal constructor(private val strings: Strings) {
    val loading: String get() = strings.text(BlameKeys.loading)
    val loadMore: String get() = strings.text(BlameKeys.loadMore)
    val ignoreWhitespace: String get() = strings.text(BlameKeys.ignoreWhitespace)
    val recurseBefore: String get() = strings.text(BlameKeys.recurseBefore)
    val unsupported: String get() = strings.text(BlameKeys.unsupported)
    val noLines: String get() = strings.text(BlameKeys.noLines)
    val loadFailed: String get() = strings.text(BlameKeys.loadFailed)
    val fileHistory: String get() = strings.text(BlameKeys.fileHistory)
    fun renamedFrom(path: String): String = strings.text(BlameKeys.renamedFrom, path)
    fun comparisonStart(path: String): String = strings.text(BlameKeys.comparisonStart, path)
    fun comparisonResult(hunkCount: Int): String = strings.text(BlameKeys.comparisonResult, hunkCount)
}

/** Blame 문구 네임스페이스 진입점. */
val Strings.blame: BlameStrings get() = BlameStrings(this)

internal val blameTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        BlameKeys.loading to "Blame을 읽는 중",
        BlameKeys.loadMore to "더 불러오기",
        BlameKeys.ignoreWhitespace to "공백 무시",
        BlameKeys.recurseBefore to "이 커밋 이전으로",
        BlameKeys.unsupported to "이 파일은 blame을 지원하지 않습니다",
        BlameKeys.noLines to "표시할 줄이 없습니다",
        BlameKeys.loadFailed to "Blame을 읽지 못했습니다",
        BlameKeys.fileHistory to "파일 이력",
        BlameKeys.renamedFrom to "이전 경로 {0}",
        BlameKeys.comparisonStart to "비교 시작: {0}",
        BlameKeys.comparisonResult to "비교 diff {0}개 hunk",
    ),
    Locale.ENGLISH to mapOf(
        BlameKeys.loading to "Loading blame",
        BlameKeys.loadMore to "Load more",
        BlameKeys.ignoreWhitespace to "Ignore whitespace",
        BlameKeys.recurseBefore to "Blame before this commit",
        BlameKeys.unsupported to "Blame is not supported for this file",
        BlameKeys.noLines to "No lines to display",
        BlameKeys.loadFailed to "Could not load blame",
        BlameKeys.fileHistory to "File history",
        BlameKeys.renamedFrom to "from {0}",
        BlameKeys.comparisonStart to "Compare from: {0}",
        BlameKeys.comparisonResult to "Comparison diff: {0} hunks",
    ),
)
