package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

class GraphDragDropStringsSpec : FunSpec({

    val catalog = builtInStringCatalog()

    test("그래프 드래그 앤 드롭 문구는 두 내장 로케일에서 키 이름으로 폴백하지 않는다") {
        listOf(Locale.KOREAN, Locale.ENGLISH).forEach { locale ->
            val copy = catalog.stringsFor(locale, devBuild = false).graphDragDrop

            listOf(
                copy.mergeOrRebase("feature", "main"),
                copy.merge("feature", "main"),
                copy.rebase("feature", "main"),
                copy.cherryPick("abc1234", "main"),
                copy.reset("main", "abc1234"),
                copy.moveTag("v1", "abc1234"),
                copy.destructiveWarning,
                copy.unsupported,
                copy.unavailableCommand,
            ).forEach { text ->
                text.isBlank() shouldBe false
                text shouldNotContain "$GRAPH_DRAG_DROP_NAMESPACE."
            }
        }
    }

    test("인자 있는 문구는 드래그한 ref와 대상 ref를 치환한다") {
        val copy = catalog.stringsFor(Locale.KOREAN, devBuild = false).graphDragDrop

        copy.mergeOrRebase("feature", "main") shouldContain "feature"
        copy.mergeOrRebase("feature", "main") shouldContain "main"
        copy.conflict("shared.kt") shouldContain "shared.kt"
        copy.merge("feature", "main") shouldNotContain "{0}"
    }
})
