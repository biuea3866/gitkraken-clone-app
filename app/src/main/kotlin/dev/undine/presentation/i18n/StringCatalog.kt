package dev.undine.presentation.i18n

import java.util.Locale

private const val MISSING_KEY_MARKER_CHARACTER = '!'
private const val DEV_BUILD_PROPERTY = "undine.dev"
private const val DEV_BUILD_PROPERTY_VALUE = "true"

/** 누락 키를 감싸는 개발 빌드 표식. 번역이 빠진 자리가 화면에서 바로 눈에 띄게 한다. */
internal val MISSING_KEY_MARKER: String =
    "$MISSING_KEY_MARKER_CHARACTER$MISSING_KEY_MARKER_CHARACTER"

/** 개발 빌드 판별 — 누락 키를 표식으로 감쌀지 결정한다. */
internal fun isDevBuild(): Boolean = System.getProperty(DEV_BUILD_PROPERTY) == DEV_BUILD_PROPERTY_VALUE

/**
 * 로케일별 번역 데이터의 집합이자 조회 진입점.
 *
 * **새 로케일은 [translations] 에 항목을 추가하는 것으로 끝난다** — 조회 호출부
 * (`strings.common.ok` 형태의 접근자)는 바뀌지 않는다.
 *
 * @param translations 로케일 → (키 → 번역). [mergeTranslations] 로 네임스페이스별 맵을 합쳐 넘긴다.
 * @param defaultLocale 미지원 로케일과 누락 키가 폴백할 로케일. 번역 데이터가 반드시 있어야 한다.
 */
class StringCatalog(
    translations: Map<Locale, Map<StringKey, String>>,
    val defaultLocale: Locale,
) {
    private val bundles: Map<Locale, Map<StringKey, String>> = LinkedHashMap(translations)

    init {
        require(bundles.containsKey(defaultLocale)) { "기본 로케일 $defaultLocale 의 번역 데이터가 없습니다" }
    }

    val supportedLocales: Set<Locale> get() = bundles.keys

    /**
     * 요청 로케일 → 실제 사용할 로케일. 정확히 일치하는 번역이 없으면 **같은 언어**의 번역을 찾고,
     * 그것도 없으면 [defaultLocale] 로 폴백한다 (`en_GB` → `en`, `fr` → `ko`).
     */
    fun resolveLocale(requested: Locale): Locale =
        when {
            bundles.containsKey(requested) -> requested
            else -> bundles.keys.firstOrNull { it.language == requested.language } ?: defaultLocale
        }

    /**
     * 요청 로케일에 대응하는 조회 API. 요청 로케일이 미지원이면 폴백된 로케일로 고정된다.
     *
     * @param devBuild 기본값은 `undine.dev` 시스템 프로퍼티다. 테스트는 명시 값을 넘겨 고정한다.
     */
    fun stringsFor(requested: Locale, devBuild: Boolean = isDevBuild()): Strings =
        Strings(locale = resolveLocale(requested), catalog = this, devBuild = devBuild)

    /**
     * 번역 조회. 현재 로케일에 없으면 기본 로케일 값으로 폴백해 **크래시하지 않는다**.
     * 개발 빌드에서는 폴백 사실을 숨기지 않고 [MISSING_KEY_MARKER] 로 감싼 키 이름을 돌려준다.
     * 어느 로케일에도 없으면 마지막 수단으로 키 이름 자체를 돌려준다.
     */
    internal fun lookup(locale: Locale, key: StringKey, devBuild: Boolean): String {
        val translated = bundles[locale]?.get(key)
        return when {
            translated != null -> translated
            devBuild -> "$MISSING_KEY_MARKER${key.id}$MISSING_KEY_MARKER"
            else -> bundles[defaultLocale]?.get(key) ?: key.id
        }
    }
}
