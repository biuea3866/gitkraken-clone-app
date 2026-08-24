package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `diff.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class DiffStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = diffTranslations, defaultLocale = DEFAULT_LOCALE)

    fun textsOf(strings: DiffStrings) = listOf(
        strings.binaryNotice,
        strings.binaryDescription,
        strings.tooLargeNotice,
        strings.tooLargeDescription,
        strings.noChangesNotice,
        strings.stageHunk,
        strings.unifiedViewMode,
        strings.splitViewMode,
    )

    test("지원 로케일마다 diff 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).diff
            textsOf(strings) shouldHaveSize 8
            textsOf(strings).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("이진 파일과 임계치 초과는 서로 다른 사유 문구로 구분된다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).diff

        korean.binaryNotice shouldNotBe korean.tooLargeNotice
        korean.binaryNotice shouldNotBe korean.noChangesNotice
        korean.tooLargeNotice shouldNotBe korean.noChangesNotice
    }

    test("사유 문구는 로케일마다 다르게 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).diff
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).diff

        korean.binaryNotice shouldNotBe english.binaryNotice
        korean.stageHunk shouldNotBe english.stageHunk
    }

    test("diff 키는 diff 네임스페이스 접두사를 쓴다") {
        DiffKeys.all.forEach { it.id.startsWith("diff.") shouldBe true }
        DiffKeys.all shouldHaveSize 8
    }
})
