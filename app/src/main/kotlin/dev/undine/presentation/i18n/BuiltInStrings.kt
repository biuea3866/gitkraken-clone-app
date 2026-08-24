package dev.undine.presentation.i18n

import java.util.Locale

/** 미지원 로케일과 누락 키가 폴백하는 기본 로케일. */
val DEFAULT_LOCALE: Locale = Locale.KOREAN

/**
 * 앱에 내장된 번역 데이터 — **네임스페이스별 맵의 목록**이다.
 *
 * 새 화면 티켓은 자기 파일에 `Map<Locale, Map<StringKey, String>>` 을 정의하고 이 목록에 한 줄만
 * 추가한다. 새 로케일은 각 네임스페이스 맵에 로케일 항목을 추가하면 되고, 어느 쪽이든
 * 조회 호출부는 바뀌지 않는다.
 */
internal val builtInTranslations: List<Map<Locale, Map<StringKey, String>>> =
    listOf(
        commonTranslations,
        timeTranslations,
        shellTranslations,
        welcomeTranslations,
        sidebarTranslations,
        graphTranslations,
        commitDetailTranslations,
        diffTranslations,
        toolbarTranslations,
        searchTranslations,
        paletteTranslations,
        stagingTranslations,
        conflictTranslations,
        rebaseTranslations,
    )

/** 네임스페이스별 번역을 로케일 단위로 합친다. 같은 키가 겹치면 뒤에 오는 쪽이 이긴다. */
internal fun mergeTranslations(
    sources: List<Map<Locale, Map<StringKey, String>>>,
): Map<Locale, Map<StringKey, String>> {
    val merged = LinkedHashMap<Locale, MutableMap<StringKey, String>>()
    sources.forEach { source ->
        source.forEach { (locale, entries) ->
            merged.getOrPut(locale) { LinkedHashMap() }.putAll(entries)
        }
    }
    return merged.mapValues { (_, entries) -> entries.toMap() }
}

/** 내장 번역으로 만든 카탈로그. */
fun builtInStringCatalog(): StringCatalog =
    StringCatalog(translations = mergeTranslations(builtInTranslations), defaultLocale = DEFAULT_LOCALE)

/**
 * 시스템 로케일 기준 조회 API. 로케일 출처는 시스템 로케일 하나뿐이며,
 * 사용자 설정 로케일 연동은 `Settings` 확장이 필요해 후속 티켓 소관이다.
 */
fun systemStrings(): Strings = builtInStringCatalog().stringsFor(Locale.getDefault())
