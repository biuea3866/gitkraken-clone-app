package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.Locale

class TabsStringsSpec : FunSpec({

    test("탭 문자열은 지원 로케일마다 모두 번역돼 있다") {
        val catalog = StringCatalog(tabsTranslations, DEFAULT_LOCALE)

        catalog.supportedLocales.forEach { locale ->
            val tabs = catalog.stringsFor(locale, devBuild = true).tabs
            listOf(tabs.closeTab, tabs.closeTabConfirmation, tabs.missingPath).forEach(String::shouldNotBeBlank)
        }
    }

    test("탭 문자열은 tabs 네임스페이스를 사용한다") {
        TabsKeys.run {
            listOf(closeTab, closeTabConfirmation, missingPath).forEach { key ->
                key.id.startsWith("tabs.") shouldBe true
            }
        }
    }

    test("한국어와 영어 탭 문구는 서로 다르다") {
        val catalog = StringCatalog(tabsTranslations, DEFAULT_LOCALE)

        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).tabs
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).tabs

        korean.missingPath shouldNotBe english.missingPath
    }
})
