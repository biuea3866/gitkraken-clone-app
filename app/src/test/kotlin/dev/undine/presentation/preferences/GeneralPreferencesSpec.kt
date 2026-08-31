package dev.undine.presentation.preferences

import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.SettingsPreference
import dev.undine.domain.ThemeMode
import dev.undine.presentation.i18n.builtInStringCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException
import java.util.Locale

private val CATALOG = builtInStringCatalog()
private val TEXTS = PREFERENCES_TEST_STRINGS

/** 일반 탭이 다루지 않는 값도 함께 담아 둔다 — 한 항목 복원이 나머지를 건드리는지 보려면 필요하다. */
private val STORED = Settings.DEFAULTS.copy(
    recentRepositories = listOf(RepositoryPath("/tmp/repo")),
    theme = ThemeMode.DARK,
    language = "en",
    reopenLastRepository = true,
    defaultBranchName = "trunk",
    tabWidth = 2,
)

private class FakeGeneralSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var saveFailure: IOException? = null

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        saveFailure?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        saveFailure?.let { throw it }
        stored = transform(stored)
    }
}

/** 저장이 곧바로 끝나는 상태 홀더. 조작 직후의 화면 값을 그대로 읽는다. */
private class GeneralFixture(initial: Settings = STORED) {
    val gateway = FakeGeneralSettingsGateway(initial)

    val state: PreferencesState = PreferencesState(
        scope = CoroutineScope(Dispatchers.Unconfined + Job()),
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    ).also { it.refresh() }
}

class GeneralPreferencesSpec : FunSpec({

    test("테마 행은 저장된 테마를 앱 출처로 보여주고 테마 항목만 되돌릴 수 있게 한다") {
        val row = themePreferencesRow(STORED, TEXTS)

        row.label shouldBe TEXTS.theme
        row.value shouldBe TEXTS.themeDark
        row.source shouldBe PreferenceValueSource.APP_SETTINGS
        row.restorablePreference shouldBe SettingsPreference.THEME
    }

    test("테마 선택지는 라이트·다크·시스템 셋이고 저장된 값만 선택으로 표시된다") {
        val choices = themeChoices(STORED, TEXTS)

        choices.map { it.value } shouldBe listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
        choices.map { it.label } shouldBe listOf(TEXTS.themeLight, TEXTS.themeDark, TEXTS.themeSystem)
        choices.filter { it.selected }.map { it.value } shouldBe listOf(ThemeMode.DARK)
    }

    test("테마를 라이트로 고르면 저장되고 화면 값도 저장 결과를 따라간다") {
        val fixture = GeneralFixture()

        fixture.state.selectTheme(ThemeMode.LIGHT)

        fixture.gateway.stored.theme shouldBe ThemeMode.LIGHT
        fixture.state.settings.theme shouldBe ThemeMode.LIGHT
        themePreferencesRow(fixture.state.settings, TEXTS).value shouldBe TEXTS.themeLight
    }

    test("언어를 저장하지 않았으면 시스템 로케일을 따른다고 표시한다") {
        Settings.DEFAULTS.language shouldBe null

        val row = languagePreferencesRow(Settings.DEFAULTS, CATALOG, TEXTS)

        row.value shouldBe TEXTS.languageSystem
        row.restorablePreference shouldBe SettingsPreference.LANGUAGE
    }

    test("카탈로그가 지원하는 태그는 그 로케일 이름으로 표시된다") {
        languagePreferencesRow(STORED, CATALOG, TEXTS).value shouldBe
            Locale.ENGLISH.getDisplayLanguage(Locale.ENGLISH)
    }

    test("정확히 일치하지 않아도 같은 언어의 번역이 있으면 그 로케일로 표시된다") {
        val stored = STORED.copy(language = "en-GB")

        languagePreferencesRow(stored, CATALOG, TEXTS).value shouldBe
            Locale.ENGLISH.getDisplayLanguage(Locale.ENGLISH)
        stored.language shouldBe "en-GB"
    }

    test("카탈로그에 없는 태그는 카탈로그 기본 로케일로 표시하되 저장값은 그대로 둔다") {
        val fixture = GeneralFixture(STORED.copy(language = "fr-FR"))

        languagePreferencesRow(fixture.state.settings, CATALOG, TEXTS).value shouldBe
            CATALOG.defaultLocale.getDisplayLanguage(CATALOG.defaultLocale)
        fixture.state.settings.language shouldBe "fr-FR"
        fixture.gateway.stored.language shouldBe "fr-FR"
    }

    test("언어 선택지는 시스템 항목 뒤에 카탈로그가 지원하는 로케일을 늘어놓는다") {
        val choices = languageChoices(STORED, CATALOG, TEXTS)

        choices.first().label shouldBe TEXTS.languageSystem
        choices.first().value shouldBe null
        choices.drop(1).map { it.value } shouldBe CATALOG.supportedLocales.map(Locale::toLanguageTag)
        choices.filter { it.selected }.map { it.value } shouldBe listOf(Locale.ENGLISH.toLanguageTag())
    }

    test("미지원 태그가 저장돼 있으면 폴백 로케일이 선택으로 잠기지 않아 직접 고를 수 있다") {
        val fixture = GeneralFixture(STORED.copy(language = "fr-FR"))
        val fallbackTag = CATALOG.defaultLocale.toLanguageTag()

        val choices = languageChoices(fixture.state.settings, CATALOG, TEXTS)

        // 표시는 폴백을 따르지만(AC 5) 선택은 아무것도 잡히지 않는다 — 전부 고를 수 있어야 한다.
        choices.filter { it.selected }.shouldBeEmpty()
        languagePreferencesRow(fixture.state.settings, CATALOG, TEXTS).value shouldBe
            CATALOG.defaultLocale.getDisplayLanguage(CATALOG.defaultLocale)

        fixture.state.selectLanguage(choices.single { it.value == fallbackTag }.value)

        fixture.gateway.stored.language shouldBe fallbackTag
        fixture.state.settings.language shouldBe fallbackTag
        languageChoices(fixture.state.settings, CATALOG, TEXTS)
            .filter { it.selected }.map { it.value } shouldBe listOf(fallbackTag)
    }

    test("같은 언어로 폴백해 표시되더라도 태그가 다르면 그 선택지를 선택으로 보지 않는다") {
        val stored = STORED.copy(language = "en-GB")

        languageChoices(stored, CATALOG, TEXTS).filter { it.selected }.shouldBeEmpty()
    }

    test("언어를 고르면 그 태그가 저장된다") {
        val fixture = GeneralFixture()

        fixture.state.selectLanguage(null)

        fixture.gateway.stored.language shouldBe null
        fixture.state.settings.language shouldBe null
        languagePreferencesRow(fixture.state.settings, CATALOG, TEXTS).value shouldBe TEXTS.languageSystem
    }

    test("마지막 저장소 다시 열기는 켬·끔으로 표시되고 두 선택지를 내준다") {
        reopenLastRepositoryRow(STORED, TEXTS).value shouldBe TEXTS.enabled
        reopenLastRepositoryRow(STORED.copy(reopenLastRepository = false), TEXTS).value shouldBe TEXTS.disabled

        val choices = reopenLastRepositoryChoices(STORED, TEXTS)
        choices.map { it.value } shouldBe listOf(true, false)
        choices.filter { it.selected }.map { it.value } shouldBe listOf(true)
    }

    test("마지막 저장소 다시 열기를 끄면 저장된다") {
        val fixture = GeneralFixture()

        fixture.state.selectReopenLastRepository(false)

        fixture.gateway.stored.reopenLastRepository shouldBe false
        fixture.state.settings.reopenLastRepository shouldBe false
    }

    test("항목별 기본값 복원은 그 항목만 되돌리고 나머지 일반 설정은 그대로 둔다") {
        val fixture = GeneralFixture()

        fixture.state.restoreDefault(SettingsPreference.THEME)

        fixture.state.settings.theme shouldBe Settings.DEFAULT_THEME
        fixture.state.settings.language shouldBe STORED.language
        fixture.state.settings.reopenLastRepository shouldBe STORED.reopenLastRepository
        fixture.state.settings.defaultBranchName shouldBe STORED.defaultBranchName
    }

    test("언어 복원은 언어만 시스템 로케일로 되돌린다") {
        val fixture = GeneralFixture()

        fixture.state.restoreDefault(SettingsPreference.LANGUAGE)

        fixture.state.settings.language shouldBe null
        fixture.state.settings.theme shouldBe STORED.theme
        fixture.state.settings.reopenLastRepository shouldBe STORED.reopenLastRepository
    }

    test("다시 열기 복원은 그 항목만 되돌린다") {
        val fixture = GeneralFixture()

        fixture.state.restoreDefault(SettingsPreference.REOPEN_LAST_REPOSITORY)

        fixture.state.settings.reopenLastRepository shouldBe Settings.DEFAULTS.reopenLastRepository
        fixture.state.settings.theme shouldBe STORED.theme
        fixture.state.settings.language shouldBe STORED.language
    }

    test("쓰기에 실패하면 고른 값이 화면에 남지 않고 마지막 저장 성공값에 머문다") {
        val fixture = GeneralFixture()
        fixture.gateway.saveFailure = IOException("설정 파일을 쓸 수 없습니다")

        fixture.state.selectTheme(ThemeMode.LIGHT)

        fixture.state.settings.theme shouldBe ThemeMode.DARK
        fixture.state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
        themePreferencesRow(fixture.state.settings, TEXTS).value shouldBe TEXTS.themeDark
    }

    test("domain 이 거부한 값은 저장되지 않고 화면도 이전 값에 머문다") {
        val fixture = GeneralFixture()

        fixture.state.apply { it.copy(defaultBranchName = " ") }

        fixture.state.settings.defaultBranchName shouldBe STORED.defaultBranchName
        fixture.state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
        themePreferencesRow(fixture.state.settings, TEXTS).value shouldBe TEXTS.themeDark
    }
})
