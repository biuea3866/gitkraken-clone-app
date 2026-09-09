package dev.undine.presentation.i18n

import dev.undine.domain.graphops.GraphDropRefusal
import java.util.Locale

/**
 * `contextmenu.*` 네임스페이스 — 우클릭 메뉴가 내는 항목 이름과 비활성 사유 표시.
 *
 * 그래프 조작 자체의 문구는 `graphdragdrop.*`([GraphDragDropKeys])가 이미 갖고 있다. 이 파일은
 * **메뉴라는 표면의 문구**를 담는다 — 팔레트 명령 제목("병합")과 달리 메뉴 항목은 대상과의 관계까지
 * 읽혀야 한다("이 브랜치를 현재 브랜치에 병합").
 *
 * 화면은 문구를 여기서만 읽는다 — [ContextMenuStrings] 가 사유 문장까지 함께 내주므로 컨텍스트
 * 메뉴 코드에 문자열 리터럴이 남지 않는다.
 */
internal const val CONTEXT_MENU_NAMESPACE: String = "contextmenu"

/** 우클릭 메뉴 문구 키. */
object ContextMenuKeys {
    val menuName = StringKey("$CONTEXT_MENU_NAMESPACE.menuName")
    val itemMerge = StringKey("$CONTEXT_MENU_NAMESPACE.itemMerge")
    val itemRebase = StringKey("$CONTEXT_MENU_NAMESPACE.itemRebase")
    val itemCherryPick = StringKey("$CONTEXT_MENU_NAMESPACE.itemCherryPick")
    val itemReset = StringKey("$CONTEXT_MENU_NAMESPACE.itemReset")
    val itemMoveTag = StringKey("$CONTEXT_MENU_NAMESPACE.itemMoveTag")
    val blockedItem = StringKey("$CONTEXT_MENU_NAMESPACE.blockedItem")
}

/** 우클릭 메뉴 문구 접근자. */
@JvmInline
value class ContextMenuStrings internal constructor(private val strings: Strings) {

    /** 메뉴 자체의 접근성 이름 — 화면 낭독기가 무엇이 열렸는지 말할 수 있어야 한다. */
    val menuName: String get() = strings.text(ContextMenuKeys.menuName)

    val itemMerge: String get() = strings.text(ContextMenuKeys.itemMerge)
    val itemRebase: String get() = strings.text(ContextMenuKeys.itemRebase)
    val itemCherryPick: String get() = strings.text(ContextMenuKeys.itemCherryPick)
    val itemReset: String get() = strings.text(ContextMenuKeys.itemReset)
    val itemMoveTag: String get() = strings.text(ContextMenuKeys.itemMoveTag)

    /**
     * 비활성 항목의 표시. **항목을 숨기지 않고 사유를 붙인다** — 사라지면 사용자는 그 조작이
     * 존재하지 않는다고 읽는다 (결정 D3).
     */
    fun blockedItem(label: String, reason: String): String =
        strings.text(ContextMenuKeys.blockedItem, label, reason)

    /**
     * 지금 실행할 수 없는 사유 문장. 드래그&드롭이 쓰는 **같은 문장**을 그대로 읽는다 —
     * 같은 사유를 두 벌 번역하면 하나가 곧 어긋난다 (결정 D2).
     */
    fun reason(refusal: GraphDropRefusal): String = strings.graphDragDrop.refusal(refusal)
}

/** 우클릭 메뉴 문구 네임스페이스 진입점. */
val Strings.contextMenu: ContextMenuStrings get() = ContextMenuStrings(this)

internal val contextMenuTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        ContextMenuKeys.menuName to "그래프 조작 메뉴",
        ContextMenuKeys.itemMerge to "이 브랜치를 현재 브랜치에 병합",
        ContextMenuKeys.itemRebase to "현재 브랜치를 이 브랜치 위로 리베이스",
        ContextMenuKeys.itemCherryPick to "이 커밋을 현재 브랜치에 cherry-pick",
        ContextMenuKeys.itemReset to "이 브랜치를 선택한 커밋으로 reset",
        ContextMenuKeys.itemMoveTag to "이 태그를 선택한 커밋으로 이동",
        ContextMenuKeys.blockedItem to "{0} — 지금은 할 수 없습니다: {1}",
    ),
    Locale.ENGLISH to mapOf(
        ContextMenuKeys.menuName to "Graph operations menu",
        ContextMenuKeys.itemMerge to "Merge this branch into the current branch",
        ContextMenuKeys.itemRebase to "Rebase the current branch onto this branch",
        ContextMenuKeys.itemCherryPick to "Cherry-pick this commit onto the current branch",
        ContextMenuKeys.itemReset to "Reset this branch to the selected commit",
        ContextMenuKeys.itemMoveTag to "Move this tag to the selected commit",
        ContextMenuKeys.blockedItem to "{0} — not available now: {1}",
    ),
)
