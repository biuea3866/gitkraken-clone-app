package dev.undine.presentation.contextmenu

import dev.undine.domain.graphops.GraphOperation

/**
 * 화면 테스트가 우클릭 메뉴를 집는 태그.
 *
 * 항목 태그는 **조작 종류**로 만든다 — 목록 순서는 대상에 따라 달라지므로 인덱스로는 같은 항목을
 * 집을 수 없다.
 */
object ContextMenuTags {
    const val MENU = "contextmenu.menu"

    /** 메뉴 밖을 덮는 영역. 여기서의 클릭은 메뉴만 닫고 아래 행으로 가지 않는다. */
    const val SCRIM = "contextmenu.scrim"

    private const val ITEM_PREFIX = "contextmenu.item."

    fun item(kind: GraphOperationKind): String = "$ITEM_PREFIX${kind.name}"
}

/** 메뉴 항목의 종류. 태그·라벨이 조작 타입 하나로 갈리므로 닫힌 목록으로 둔다. */
enum class GraphOperationKind {
    MERGE,
    REBASE,
    CHERRY_PICK,
    RESET_BRANCH,
    MOVE_TAG,
}

/** 이 조작이 어느 종류인가. `when` 이 전수 분기라 새 조작이 들어오면 컴파일이 막는다. */
fun GraphOperation.kind(): GraphOperationKind = when (this) {
    is GraphOperation.Merge -> GraphOperationKind.MERGE
    is GraphOperation.Rebase -> GraphOperationKind.REBASE
    is GraphOperation.CherryPick -> GraphOperationKind.CHERRY_PICK
    is GraphOperation.ResetBranch -> GraphOperationKind.RESET_BRANCH
    is GraphOperation.MoveTag -> GraphOperationKind.MOVE_TAG
}
