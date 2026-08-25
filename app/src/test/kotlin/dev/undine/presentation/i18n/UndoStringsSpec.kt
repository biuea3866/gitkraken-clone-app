package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.Locale

/** Undo 화면은 domain 의 진단 문구가 아니라 자기 로케일 리소스를 표시한다. */
class UndoStringsSpec : FunSpec({

    val catalog = StringCatalog(translations = undoTranslations, defaultLocale = DEFAULT_LOCALE)

    test("한국어 지정 불가 문구는 제품 계약과 일치한다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = true).undo

        strings.nothingToUndo shouldBe "되돌릴 작업이 없습니다"
        strings.irreversible("push") shouldBe "push 는 되돌릴 수 없습니다"
        strings.externalChange shouldBe "저장소가 외부에서 변경되어 되돌릴 수 없습니다"
    }

    test("지원 로케일마다 모든 Undo 키가 비어 있지 않다") {
        catalog.supportedLocales.forEach { locale ->
            val strings = catalog.stringsFor(locale, devBuild = true).undo
            listOf(
                strings.idleLabel,
                strings.undoLabel("Commit"),
                strings.undoTooltip("Commit", "target"),
                strings.nothingToUndo,
                strings.irreversible("Push"),
                strings.externalChange,
                strings.historyTitle,
                strings.historyReversible,
                strings.historyIrreversible,
            ).forEach(String::shouldNotBeBlank)
        }
    }
})
