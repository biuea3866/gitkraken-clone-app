package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/**
 * `sidebar.*` 네임스페이스. [builtInTranslations] 등록 한 줄은 UND-26 이 일괄 추가하므로
 * 여기서는 자기 맵으로 카탈로그를 만들어 검증한다 (wave 3 결정 A3).
 */
class SidebarStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = sidebarTranslations, defaultLocale = DEFAULT_LOCALE)

    fun sidebarOf(locale: Locale) = catalog.stringsFor(locale, devBuild = true).sidebar

    test("지원 로케일마다 sidebar 키가 모두 번역돼 있다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)

        catalog.supportedLocales.forEach { locale ->
            val sidebar = sidebarOf(locale)
            listOf(
                sidebar.localBranches,
                sidebar.remoteBranches,
                sidebar.tags,
                sidebar.stashes,
                sidebar.detachedHead,
                sidebar.filterLabel,
                sidebar.emptyBranches,
                sidebar.emptyFiltered("x"),
                sidebar.currentBranch,
                sidebar.untrackedStash,
                sidebar.menuOpen,
                sidebar.menuCheckout,
                sidebar.menuRename,
                sidebar.menuDelete,
                sidebar.menuMerge,
                sidebar.deleteTitle,
                sidebar.deleteMessage("main"),
                sidebar.unmergedTitle,
                sidebar.unmergedMessage("main"),
                sidebar.renameTitle,
                sidebar.renameField,
                sidebar.loadFailed("사유"),
                sidebar.actionFailed("사유"),
                sidebar.ahead(2),
                sidebar.behind(1),
            ).forEach {
                it.shouldNotBeBlank()
                it shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("미병합 삭제 경고는 커밋이 도달 불가가 된다는 사실을 문장으로 담는다") {
        sidebarOf(Locale.KOREAN).unmergedMessage("feature/wip") shouldContain "도달 불가"
        sidebarOf(Locale.KOREAN).unmergedMessage("feature/wip") shouldContain "feature/wip"
        sidebarOf(Locale.ENGLISH).unmergedMessage("feature/wip") shouldContain "unreachable"
    }

    test("ahead·behind 배지는 방향 기호와 커밋 수를 함께 낸다") {
        val korean = sidebarOf(Locale.KOREAN)

        korean.ahead(2) shouldBe "2↑"
        korean.behind(1) shouldBe "1↓"
    }

    test("필터 빈 상태 문구는 입력한 필터 문자열을 담는다") {
        sidebarOf(Locale.KOREAN).emptyFiltered("feat") shouldContain "feat"
        sidebarOf(Locale.ENGLISH).emptyFiltered("feat") shouldContain "feat"
    }

    test("sidebar 키는 sidebar 네임스페이스 접두사를 쓴다") {
        sidebarTranslations.getValue(DEFAULT_LOCALE).keys.forEach {
            it.id.startsWith("sidebar.") shouldBe true
        }
    }

    test("두 로케일의 키 집합이 서로 어긋나지 않는다") {
        val korean = sidebarTranslations.getValue(Locale.KOREAN).keys
        val english = sidebarTranslations.getValue(Locale.ENGLISH).keys

        english shouldBe korean
    }
})
