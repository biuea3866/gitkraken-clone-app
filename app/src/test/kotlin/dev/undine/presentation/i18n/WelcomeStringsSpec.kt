package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `welcome.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class WelcomeStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = welcomeTranslations, defaultLocale = DEFAULT_LOCALE)

    fun allTexts(strings: WelcomeStrings) = listOf(
        strings.title,
        strings.recentTitle,
        strings.recentEmpty,
        strings.recentEmptyDescription,
        strings.recentUnavailable,
        strings.recentRemove,
        strings.openTitle,
        strings.openAction,
        strings.cloneTitle,
        strings.cloneUrlLabel,
        strings.cloneTargetLabel,
        strings.cloneStart,
        strings.cloneCancel,
        strings.errorNotFound,
        strings.errorNotARepository,
        strings.errorPermissionDenied,
        strings.errorBareRepository,
        strings.errorOpenFailed,
        strings.errorAuthentication,
        strings.errorTargetNotEmpty,
        strings.errorCloneFailed,
    )

    test("지원 로케일마다 welcome 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).welcome
            allTexts(strings).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
            strings.cleanupFailed("/tmp/leftover") shouldNotContain MISSING_KEY_MARKER
            strings.cloneProgress("Receiving objects", 42) shouldNotContain MISSING_KEY_MARKER
        }
    }

    test("welcome 키는 welcome 네임스페이스 접두사를 쓴다") {
        WelcomeKeys.all.forEach { it.id.startsWith("welcome.") shouldBe true }
    }

    test("네 가지 열기 실패 사유가 서로 다른 문구를 쓴다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false).welcome
        val messages = listOf(
            strings.errorNotFound,
            strings.errorNotARepository,
            strings.errorPermissionDenied,
            strings.errorBareRepository,
        )

        messages.distinct().size shouldBe messages.size
    }

    test("정리 실패 안내는 수동으로 지울 경로를 담는다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false).welcome

        strings.cleanupFailed("/tmp/leftover") shouldContain "/tmp/leftover"
    }

    test("진행 안내는 단계명과 백분율을 담는다") {
        val strings = catalog.stringsFor(Locale.ENGLISH, devBuild = false).welcome

        val text = strings.cloneProgress("Receiving objects", 42)

        text shouldContain "Receiving objects"
        text shouldContain "42"
    }

    test("인증 실패 안내는 키체인·SSH 설정 확인을 가리킨다") {
        catalog.stringsFor(Locale.ENGLISH, devBuild = false).welcome.errorAuthentication.lowercase()
            .shouldContain("ssh")
    }
})
