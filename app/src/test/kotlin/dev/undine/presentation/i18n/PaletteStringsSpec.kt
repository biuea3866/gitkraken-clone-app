package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `palette.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class PaletteStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = paletteTranslations, defaultLocale = DEFAULT_LOCALE)

    test("지원 로케일마다 palette 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).palette
            listOf(strings.searchPlaceholder, strings.noCommands, strings.noResults).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("팔레트 문구는 로케일마다 다른 문구로 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).palette
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).palette

        korean.searchPlaceholder shouldBe "명령 검색"
        english.searchPlaceholder shouldBe "Search commands"
        korean.noCommands shouldBe "등록된 명령이 없습니다"
        english.noCommands shouldBe "No commands registered"
        korean.noResults shouldBe "일치하는 명령이 없습니다"
        english.noResults shouldBe "No matching commands"
    }

    test("palette 키는 palette 네임스페이스 접두사를 쓴다") {
        listOf(PaletteKeys.searchPlaceholder, PaletteKeys.noCommands, PaletteKeys.noResults).forEach {
            it.id.startsWith("palette.") shouldBe true
        }
    }
})
