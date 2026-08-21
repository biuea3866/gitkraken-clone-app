package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `shell.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class ShellStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = shellTranslations, defaultLocale = DEFAULT_LOCALE)

    test("지원 로케일마다 shell 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).shell
            listOf(strings.sidebarSplitter, strings.bottomSplitter).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("분할선 설명은 로케일마다 다른 문구로 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).shell
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).shell

        korean.sidebarSplitter shouldBe "사이드바 크기 조절"
        english.sidebarSplitter shouldBe "Resize sidebar"
        korean.bottomSplitter shouldBe "하단 영역 크기 조절"
        english.bottomSplitter shouldBe "Resize bottom panel"
    }

    test("shell 키는 shell 네임스페이스 접두사를 쓴다") {
        listOf(ShellKeys.sidebarSplitter, ShellKeys.bottomSplitter).forEach {
            it.id.startsWith("shell.") shouldBe true
        }
    }
})
