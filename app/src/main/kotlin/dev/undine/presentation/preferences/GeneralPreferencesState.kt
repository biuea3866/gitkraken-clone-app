package dev.undine.presentation.preferences

import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.domain.ThemeMode
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.i18n.StringCatalog
import java.util.Locale

/**
 * 일반 탭의 행 조립과 선택지 판정 — 컴포지션 없이 판단할 수 있는 부분만 모았다.
 *
 * **여기에는 상태가 없다.** 화면은 저장 결과로만 갱신하므로(결정 G12) 고른 값을 담아 둘 자리가
 * 필요하지 않다. 전부 저장된 [Settings] 에서 표시 값을 유도하는 순수 함수라, Compose UI 테스트
 * 없이 검증된다.
 */

/** 테마 행 — 저장된 테마를 앱 출처로 보여주고 테마 항목만 되돌린다. */
internal fun themePreferencesRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.theme,
        value = settings.theme.labelIn(texts),
        preference = SettingsPreference.THEME,
        texts = texts,
    )

/** 테마 선택지. `ThemeMode` 를 전수 늘어놓아 값이 늘면 화면도 함께 는다. */
internal fun themeChoices(
    settings: Settings,
    texts: PreferencesStrings,
): List<GeneralPreferenceChoice<ThemeMode>> = ThemeMode.entries.map { theme ->
    GeneralPreferenceChoice(
        label = theme.labelIn(texts),
        value = theme,
        selected = theme == settings.theme,
    )
}

/**
 * 언어 행 — 저장된 BCP 47 태그가 실제로 어떤 로케일로 보이는지를 표시한다.
 *
 * 카탈로그에 없는 태그는 [StringCatalog.resolveLocale] 계약대로 기본 로케일로 **보이기만** 한다.
 * 저장값을 그 로케일로 바꾸지 않는다 — 나중에 그 번역이 더해지면 저장해 둔 태그가 되살아난다.
 */
internal fun languagePreferencesRow(
    settings: Settings,
    catalog: StringCatalog,
    texts: PreferencesStrings,
): PreferencesRow = appPreferencesRow(
    label = texts.language,
    value = displayedLocale(settings, catalog).labelIn(texts),
    preference = SettingsPreference.LANGUAGE,
    texts = texts,
)

/**
 * 언어 선택지 — 시스템 로케일(`null`) 다음에 카탈로그가 지원하는 로케일이 온다.
 *
 * 목록을 앱이 따로 들고 있지 않고 [StringCatalog.supportedLocales] 에서 얻는다. 두 곳에 적으면
 * 번역을 더했는데 고를 수 없는 로케일이 생긴다.
 *
 * **선택 판정에는 폴백을 쓰지 않는다.** 저장된 태그와 정확히 같은 로케일만 선택으로 본다 — 표시용
 * 폴백([displayedLocale])을 선택으로도 쓰면 `fr-FR` 처럼 미지원 태그가 저장돼 있을 때 폴백 로케일
 * 선택지가 "이미 그 값" 으로 잠겨, 정작 그 로케일을 고르려는 사람이 고를 수 없다. 미지원 태그가
 * 저장된 상태에서는 아무것도 선택되지 않고 전부 고를 수 있다.
 */
internal fun languageChoices(
    settings: Settings,
    catalog: StringCatalog,
    texts: PreferencesStrings,
): List<GeneralPreferenceChoice<String?>> {
    val saved = settings.language?.let(Locale::forLanguageTag)
    val system = GeneralPreferenceChoice<String?>(
        label = texts.languageSystem,
        value = null,
        selected = saved == null,
    )
    return listOf(system) + catalog.supportedLocales.map { locale ->
        GeneralPreferenceChoice(
            label = locale.labelIn(texts),
            value = locale.toLanguageTag(),
            selected = saved == locale,
        )
    }
}

/** 시작할 때 마지막 저장소를 여는지. */
internal fun reopenLastRepositoryRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.reopenLastRepository,
        value = settings.reopenLastRepository.asOnOff(texts),
        preference = SettingsPreference.REOPEN_LAST_REPOSITORY,
        texts = texts,
    )

internal fun reopenLastRepositoryChoices(
    settings: Settings,
    texts: PreferencesStrings,
): List<GeneralPreferenceChoice<Boolean>> = listOf(true, false).map { reopen ->
    GeneralPreferenceChoice(
        label = reopen.asOnOff(texts),
        value = reopen,
        selected = reopen == settings.reopenLastRepository,
    )
}

/** 저장된 언어 태그가 실제로 그려질 로케일. `null` 은 **시스템 로케일을 따른다**는 뜻이라 그대로 둔다. */
private fun displayedLocale(settings: Settings, catalog: StringCatalog): Locale? =
    settings.language?.let { tag -> catalog.resolveLocale(Locale.forLanguageTag(tag)) }

/** 로케일 이름은 **그 로케일의 말로** 보여준다 — 화면 언어를 바꾸려는 사람이 읽을 수 있어야 한다. */
private fun Locale?.labelIn(texts: PreferencesStrings): String =
    this?.getDisplayLanguage(this) ?: texts.languageSystem

private fun ThemeMode.labelIn(texts: PreferencesStrings): String = when (this) {
    ThemeMode.LIGHT -> texts.themeLight
    ThemeMode.DARK -> texts.themeDark
    ThemeMode.SYSTEM -> texts.themeSystem
}
