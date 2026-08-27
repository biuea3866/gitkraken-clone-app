package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.Locale

/** 6개 탭과 후속 탭 항목이 쓰는 문구 — 지원 로케일 전부에 있어야 한다. */
private fun allPreferencesTexts(texts: PreferencesStrings): List<String> = listOf(
    texts.title,
    texts.tabGeneral,
    texts.tabGit,
    texts.tabAccounts,
    texts.tabTools,
    texts.tabShortcuts,
    texts.tabAdvanced,
    texts.comingSoon,
    texts.sourceApp,
    texts.sourceGit,
    texts.restoreDefault,
    texts.resetAll,
    texts.resetAllWarning,
    texts.resetAllConfirm,
    texts.resetAllCancel,
    texts.loadFailed,
    texts.saveFailed,
    texts.enabled,
    texts.disabled,
    texts.theme,
    texts.themeLight,
    texts.themeDark,
    texts.themeSystem,
    texts.language,
    texts.languageSystem,
    texts.reopenLastRepository,
    texts.confirmDestructiveActions,
    texts.signCommits,
    texts.signTags,
    texts.signingFormat,
    texts.signingKey,
    texts.signingKeyUnset,
    texts.identityProfiles,
    texts.identityProfilesEmpty,
    texts.diffTool,
    texts.mergeTool,
    texts.toolUnset,
    texts.shortcutCommand,
    texts.shortcutBinding,
    texts.shortcutDefault,
    texts.shortcutOverridden,
    texts.updateCheck,
    texts.updateCheckInterval,
)

class PreferencesStringsSpec : FunSpec({

    test("환경설정 문자열은 지원 로케일마다 모두 번역돼 있다") {
        val catalog = StringCatalog(preferencesTranslations, DEFAULT_LOCALE)

        catalog.supportedLocales.forEach { locale ->
            // devBuild = true 면 누락 키가 표식으로 감싸져 나오므로 빈 문자열 검사만으로는 부족하다.
            val texts = catalog.stringsFor(locale, devBuild = true).preferences
            allPreferencesTexts(texts).forEach { text ->
                text.shouldNotBeBlank()
                text.startsWith(MISSING_KEY_MARKER) shouldBe false
            }
        }
    }

    test("두 로케일이 모두 등록돼 있다") {
        StringCatalog(preferencesTranslations, DEFAULT_LOCALE).supportedLocales shouldBe
            setOf(Locale.KOREAN, Locale.ENGLISH)
    }

    test("환경설정 문자열은 preferences 네임스페이스를 쓴다") {
        preferencesTranslations.getValue(DEFAULT_LOCALE).keys.forEach { key ->
            key.id.startsWith("$PREFERENCES_NAMESPACE.") shouldBe true
        }
    }

    test("두 로케일의 키 집합이 같다 — 한쪽만 번역된 키가 없다") {
        preferencesTranslations.getValue(Locale.ENGLISH).keys shouldBe
            preferencesTranslations.getValue(DEFAULT_LOCALE).keys
    }

    test("한국어와 영어 문구는 서로 다르다") {
        val catalog = StringCatalog(preferencesTranslations, DEFAULT_LOCALE)

        catalog.stringsFor(Locale.KOREAN, devBuild = false).preferences.title shouldNotBe
            catalog.stringsFor(Locale.ENGLISH, devBuild = false).preferences.title
    }

    test("배선된 카탈로그에서도 환경설정 키가 조회된다 — 등록 자리가 그대로 살아 있다") {
        val texts = builtInStringCatalog().stringsFor(Locale.KOREAN, devBuild = false).preferences

        allPreferencesTexts(texts).forEach(String::shouldNotBeBlank)
    }

    test("탭 스텁 문구에는 티켓 번호가 드러나지 않는다") {
        val catalog = StringCatalog(preferencesTranslations, DEFAULT_LOCALE)

        catalog.supportedLocales.forEach { locale ->
            catalog.stringsFor(locale, devBuild = false).preferences.comingSoon.contains("UND-") shouldBe false
        }
    }
})
