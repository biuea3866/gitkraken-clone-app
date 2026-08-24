package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `toolbar.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class ToolbarStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = toolbarTranslations, defaultLocale = DEFAULT_LOCALE)

    fun textsOf(strings: ToolbarStrings): List<String> = listOf(
        strings.fetch,
        strings.pull,
        strings.push,
        strings.moreActions,
        strings.forcePush,
        strings.forcePushWarning(branch = "main", remote = "origin"),
        strings.forcePushConfirm,
        strings.noRemote,
        strings.detachedHead,
        strings.aheadBehind(ahead = 1, behind = 2),
        strings.fetched(refCount = 3),
        strings.pulled,
        strings.pushed,
        strings.forcePushed,
        strings.nonFastForward,
        strings.remoteRejected,
        strings.authenticationFailed,
        strings.remoteNotFound,
        strings.conflict,
        strings.dirtyWorkingTree,
        strings.unexpectedFailure,
        strings.cancelling,
        strings.cancelledFetch,
        strings.cancelledPull,
        strings.cancelledPush,
        strings.cancelledForcePush,
    )

    test("지원 로케일마다 toolbar 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            textsOf(catalog.stringsFor(locale, devBuild = true).toolbar).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("toolbar 키는 toolbar 네임스페이스 접두사를 쓴다") {
        ToolbarKeys.all.forEach { it.id.startsWith("toolbar.") shouldBe true }
    }

    test("버튼 라벨은 로케일마다 다른 문구로 나온다") {
        val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).toolbar
        val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).toolbar

        korean.fetch shouldNotBe english.fetch
        korean.push shouldNotBe english.push
    }

    test("인자 있는 문구는 자리표시자를 남기지 않고 값을 채운다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false).toolbar

        strings.fetched(refCount = 3) shouldContain "3"
        strings.aheadBehind(ahead = 4, behind = 5).let {
            it shouldContain "4"
            it shouldContain "5"
        }
        strings.forcePushWarning(branch = "main", remote = "origin").let {
            it shouldContain "main"
            it shouldContain "origin"
            it shouldNotContain "{0}"
            it shouldNotContain "{1}"
        }
    }
})
