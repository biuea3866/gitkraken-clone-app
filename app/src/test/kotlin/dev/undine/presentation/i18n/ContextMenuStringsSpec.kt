package dev.undine.presentation.i18n

import dev.undine.domain.graphops.GraphDropRefusal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

/** `contextmenu.*` 네임스페이스 — 우클릭 메뉴 항목 이름과 비활성 사유 표시. */
class ContextMenuStringsSpec : FunSpec({

    val catalog = builtInStringCatalog()

    test("우클릭 메뉴 문구는 두 내장 로케일에서 키 이름으로 폴백하지 않는다") {
        listOf(Locale.KOREAN, Locale.ENGLISH).forEach { locale ->
            val copy = catalog.stringsFor(locale, devBuild = false).contextMenu

            listOf(
                copy.menuName,
                copy.itemMerge,
                copy.itemRebase,
                copy.itemCherryPick,
                copy.itemReset,
                copy.itemMoveTag,
                copy.blockedItem("항목", "사유"),
            ).forEach { text ->
                text.isBlank() shouldBe false
                text shouldNotContain "$CONTEXT_MENU_NAMESPACE."
            }
        }
    }

    test("비활성 항목 표시는 항목 이름과 사유를 함께 담는다") {
        val copy = catalog.stringsFor(Locale.KOREAN, devBuild = false).contextMenu

        val text = copy.blockedItem(copy.itemReset, copy.reason(GraphDropRefusal.SAME_COMMIT))

        text shouldContain copy.itemReset
        text shouldContain copy.reason(GraphDropRefusal.SAME_COMMIT)
        text shouldNotContain "{0}"
    }

    test("거부 사유는 드래그·드롭과 같은 문장을 쓴다") {
        val strings = catalog.stringsFor(Locale.KOREAN, devBuild = false)

        // 같은 사유를 두 벌 번역하면 하나가 곧 어긋난다 (결정 D2).
        GraphDropRefusal.entries.forEach { refusal ->
            val text = strings.contextMenu.reason(refusal)
            text.isBlank() shouldBe false
            // 키 이름이 그대로 나오면 번역이 없는 것이다 — 비어 있지 않다는 것만으로는 못 잡는다.
            text shouldNotContain "$GRAPH_DRAG_DROP_NAMESPACE."
        }
        strings.contextMenu.reason(GraphDropRefusal.SAME_REF) shouldBe strings.graphDragDrop.sameRef
        strings.contextMenu.reason(GraphDropRefusal.SAME_COMMIT) shouldBe strings.graphDragDrop.sameCommit
        strings.contextMenu.reason(GraphDropRefusal.ANNOTATED_TAG) shouldBe strings.graphDragDrop.annotatedTag
        strings.contextMenu.reason(GraphDropRefusal.UNSUPPORTED_COMBINATION) shouldBe strings.graphDragDrop.unsupported
        strings.contextMenu.reason(GraphDropRefusal.NO_CURRENT_BRANCH) shouldBe strings.graphDragDrop.noCurrentBranch
    }

    test("네임스페이스가 내장 카탈로그에 등록돼 있다") {
        val namespaces = mergeTranslations(builtInTranslations)
            .getValue(DEFAULT_LOCALE)
            .keys
            .map { it.namespace }

        namespaces shouldContainElement CONTEXT_MENU_NAMESPACE
    }
})
