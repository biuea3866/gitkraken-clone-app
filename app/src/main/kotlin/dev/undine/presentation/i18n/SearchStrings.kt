package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "search"

/** `search.*` 키 정의. 커밋 검색 화면이 노출하는 문구 전부다. */
object SearchKeys {
    val messageLabel = StringKey("$NAMESPACE.messageLabel")
    val authorLabel = StringKey("$NAMESPACE.authorLabel")
    val hashLabel = StringKey("$NAMESPACE.hashLabel")
    val pathLabel = StringKey("$NAMESPACE.pathLabel")
    val sinceLabel = StringKey("$NAMESPACE.sinceLabel")
    val untilLabel = StringKey("$NAMESPACE.untilLabel")
    val dateFormatHint = StringKey("$NAMESPACE.dateFormatHint")
    val invalidDate = StringKey("$NAMESPACE.invalidDate")
    val clear = StringKey("$NAMESPACE.clear")
    val idle = StringKey("$NAMESPACE.idle")
    val idleHint = StringKey("$NAMESPACE.idleHint")
    val searching = StringKey("$NAMESPACE.searching")
    val foundCount = StringKey("$NAMESPACE.foundCount")
    val noResults = StringKey("$NAMESPACE.noResults")
    val noResultsHint = StringKey("$NAMESPACE.noResultsHint")
    val failed = StringKey("$NAMESPACE.failed")
    val results = StringKey("$NAMESPACE.results")
}

/**
 * 검색 화면 문구 접근자. `strings.search.searching` 으로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3).
 */
@JvmInline
value class SearchStrings internal constructor(private val strings: Strings) {
    val messageLabel: String get() = strings.text(SearchKeys.messageLabel)
    val authorLabel: String get() = strings.text(SearchKeys.authorLabel)
    val hashLabel: String get() = strings.text(SearchKeys.hashLabel)
    val pathLabel: String get() = strings.text(SearchKeys.pathLabel)
    val sinceLabel: String get() = strings.text(SearchKeys.sinceLabel)
    val untilLabel: String get() = strings.text(SearchKeys.untilLabel)
    val dateFormatHint: String get() = strings.text(SearchKeys.dateFormatHint)
    val invalidDate: String get() = strings.text(SearchKeys.invalidDate)
    val clear: String get() = strings.text(SearchKeys.clear)
    val idle: String get() = strings.text(SearchKeys.idle)
    val idleHint: String get() = strings.text(SearchKeys.idleHint)
    val searching: String get() = strings.text(SearchKeys.searching)
    val noResults: String get() = strings.text(SearchKeys.noResults)
    val noResultsHint: String get() = strings.text(SearchKeys.noResultsHint)
    val failed: String get() = strings.text(SearchKeys.failed)
    val results: String get() = strings.text(SearchKeys.results)

    /** 지금까지 찾은 건수. 복수형 규칙은 코드가 아니라 로케일 패턴 안에 있다. */
    fun foundCount(count: Int): String = strings.text(SearchKeys.foundCount, count)
}

/** 검색 문구 네임스페이스 진입점. */
val Strings.search: SearchStrings get() = SearchStrings(this)

internal val searchTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        SearchKeys.messageLabel to "메시지",
        SearchKeys.authorLabel to "작성자",
        SearchKeys.hashLabel to "해시",
        SearchKeys.pathLabel to "파일 경로",
        SearchKeys.sinceLabel to "시작일",
        SearchKeys.untilLabel to "종료일",
        SearchKeys.dateFormatHint to "YYYY-MM-DD",
        SearchKeys.invalidDate to "날짜는 YYYY-MM-DD 형식으로 입력하세요",
        SearchKeys.clear to "검색 조건 지우기",
        SearchKeys.idle to "검색 조건을 입력하세요",
        SearchKeys.idleHint to "메시지·작성자·해시·경로·기간으로 커밋을 찾습니다",
        SearchKeys.searching to "검색 중",
        // 한국어는 복수 구분이 없어 수량과 무관하게 한 형태다.
        SearchKeys.foundCount to "{0}건 발견",
        SearchKeys.noResults to "검색 결과가 없습니다",
        SearchKeys.noResultsHint to "조건을 줄여 다시 시도하세요",
        SearchKeys.failed to "검색을 끝내지 못했습니다",
        SearchKeys.results to "검색 결과",
    ),
    Locale.ENGLISH to mapOf(
        SearchKeys.messageLabel to "Message",
        SearchKeys.authorLabel to "Author",
        SearchKeys.hashLabel to "Hash",
        SearchKeys.pathLabel to "File path",
        SearchKeys.sinceLabel to "From",
        SearchKeys.untilLabel to "To",
        SearchKeys.dateFormatHint to "YYYY-MM-DD",
        SearchKeys.invalidDate to "Enter the date as YYYY-MM-DD",
        SearchKeys.clear to "Clear search filters",
        SearchKeys.idle to "Enter a search condition",
        SearchKeys.idleHint to "Find commits by message, author, hash, path or date range",
        SearchKeys.searching to "Searching",
        // 영어는 one/other 두 형태 — MessageFormat 의 choice 로 리소스 안에서 갈린다.
        SearchKeys.foundCount to "{0,choice,0#no matches|1#1 match|1<{0} matches}",
        SearchKeys.noResults to "No commits matched",
        SearchKeys.noResultsHint to "Try fewer conditions",
        SearchKeys.failed to "Search did not finish",
        SearchKeys.results to "Search results",
    ),
)
