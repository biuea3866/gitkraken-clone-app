package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `search.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class SearchStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = searchTranslations, defaultLocale = DEFAULT_LOCALE)

    test("지원 로케일마다 search 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).search
            listOf(
                strings.messageLabel,
                strings.authorLabel,
                strings.hashLabel,
                strings.pathLabel,
                strings.sinceLabel,
                strings.untilLabel,
                strings.dateFormatHint,
                strings.invalidDate,
                strings.clear,
                strings.idle,
                strings.idleHint,
                strings.searching,
                strings.noResults,
                strings.noResultsHint,
                strings.failed,
                strings.results,
                strings.foundCount(3),
            ).forEach { text ->
                text.shouldNotBeBlank()
                text shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("발견 건수는 로케일 패턴이 수량을 서식한다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).search
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).search

        korean.foundCount(3) shouldBe "3건 발견"
        english.foundCount(1) shouldBe "1 match"
        english.foundCount(3) shouldContain "3 matches"
    }

    test("검색 중과 결과 0건은 서로 다른 문구다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false).search

        strings.searching shouldBe "검색 중"
        strings.noResults shouldBe "검색 결과가 없습니다"
    }

    test("search 키는 search 네임스페이스 접두사를 쓴다") {
        listOf(SearchKeys.messageLabel, SearchKeys.searching, SearchKeys.noResults).forEach { key ->
            key.id.startsWith("search.") shouldBe true
        }
    }
})
