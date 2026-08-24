package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `graph.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class GraphStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = graphTranslations, defaultLocale = DEFAULT_LOCALE)

    test("지원 로케일마다 graph 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).graph
            listOf(
                strings.emptyTitle,
                strings.emptyDescription,
                strings.errorTitle,
                strings.errorDescription,
                strings.loading,
                strings.head,
            ).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("빈 상태와 실패 상태 문구는 서로 구별된다") {
        val strings = catalog.stringsFor(DEFAULT_LOCALE, devBuild = false).graph

        (strings.emptyTitle == strings.errorTitle) shouldBe false
    }

    test("빈 상태 안내는 로케일마다 다른 문구로 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).graph
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).graph

        korean.emptyTitle shouldBe "표시할 커밋이 없습니다"
        english.emptyTitle shouldBe "No commits to show"
    }

    test("graph 키는 graph 네임스페이스 접두사를 쓴다") {
        listOf(
            GraphKeys.emptyTitle,
            GraphKeys.emptyDescription,
            GraphKeys.errorTitle,
            GraphKeys.errorDescription,
            GraphKeys.loading,
            GraphKeys.head,
        ).forEach {
            it.id.startsWith("graph.") shouldBe true
        }
    }
})
