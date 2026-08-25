package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

class SubmoduleWorktreeStringsSpec : FunSpec({
    val catalog = StringCatalog(submoduleWorktreeTranslations, DEFAULT_LOCALE)

    test("지원 로케일마다 서브모듈·worktree 패널 문구를 모두 제공한다") {
        catalog.supportedLocales shouldBe setOf(Locale.KOREAN, Locale.ENGLISH)
        catalog.supportedLocales.forEach { locale ->
            val texts = catalog.stringsFor(locale, devBuild = true).submoduleWorktree
            listOf(
                texts.submodulesTitle,
                texts.submodulesEmpty,
                texts.worktreesTitle,
                texts.worktreesEmpty,
                texts.initialize,
                texts.updateFromParent,
                texts.open,
                texts.commitToParent,
                texts.remove,
                texts.prune,
                texts.dirtyRemovalWarning(2),
            ).forEach { text ->
                text.shouldNotBeBlank()
                text shouldNotContain MISSING_KEY_MARKER
            }
        }
    }

    test("더티 제거 경고는 파일 수와 제거하지 않았음을 함께 알린다") {
        catalog.stringsFor(Locale.KOREAN).submoduleWorktree.dirtyRemovalWarning(2) shouldContain "2"
        catalog.stringsFor(Locale.KOREAN).submoduleWorktree.dirtyRemovalWarning(2) shouldContain "제거하지"
    }
})
