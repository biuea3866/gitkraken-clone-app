package dev.undine.presentation.i18n

import java.util.Locale

/**
 * 저장된 언어 태그가 실제로 그려질 로케일. `null` 은 **시스템 로케일을 따른다**는 뜻이라 그대로 둔다.
 *
 * 카탈로그에 없는 태그는 [StringCatalog.resolveLocale] 계약대로 폴백한다 — 저장값을 그 로케일로
 * 바꾸지 않는다. 나중에 그 번역이 더해지면 저장해 둔 태그가 되살아난다.
 *
 * **이 규칙은 여기 한 곳에만 둔다.** 배선(`AppRoot`)과 설정 화면이 각자 정의하면 화면이 보여주는
 * 언어와 실제로 그려지는 언어가 갈린다.
 *
 * @param languageTag `Settings.language` 의 IETF BCP 47 태그.
 */
fun StringCatalog.localeForLanguageTag(languageTag: String?): Locale? =
    languageTag?.let { tag -> resolveLocale(Locale.forLanguageTag(tag)) }

/** [localeForLanguageTag] 가 정한 로케일의 조회 API. 태그가 없으면 시스템 로케일로 그린다. */
fun StringCatalog.stringsForLanguageTag(languageTag: String?): Strings =
    stringsFor(localeForLanguageTag(languageTag) ?: Locale.getDefault())
