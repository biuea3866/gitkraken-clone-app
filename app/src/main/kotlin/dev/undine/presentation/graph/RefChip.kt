package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.presentation.design.UndineTokens
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropTarget
import dev.undine.presentation.contextmenu.GraphContextMenuBinding
import dev.undine.presentation.contextmenu.GraphContextTarget
import dev.undine.presentation.contextmenu.contextMenuTrigger
import dev.undine.presentation.i18n.graph
import dev.undine.presentation.i18n.strings

/** 칩 배경의 불투명도. 글자 대비를 지키려면 옅어야 한다 — 색은 식별용이고 글자가 정보다. */
private const val CHIP_FILL_ALPHA = 0.18f

/**
 * 커밋 행에 붙는 참조 칩 — HEAD·브랜치·태그.
 *
 * 색은 종류로 갈린다: HEAD 는 강조색, 태그는 경고색, 브랜치는 본문색이다. 값은 전부 디자인 토큰이며
 * 여기에 색 리터럴을 두지 않는다 (compose-ui 규칙 5).
 *
 * 브랜치·태그 라벨은 참조 이름 그대로이고, **HEAD 라벨은 `graph.head` 번역에서 읽는다** —
 * 표시 문자열을 하드코딩하거나 호출부에서 받아오지 않는다.
 *
 * 칩 우클릭은 그 참조를 대상으로 조작 메뉴를 연다 ([contextMenu]). 드래그와 **같은 대상 판정**을
 * 쓰지만 산출은 [dev.undine.presentation.contextmenu.graphOperationsFor] 하나가 한다.
 */
@Composable
internal fun RefChip(
    chip: GraphRefChip,
    modifier: Modifier = Modifier,
    dragDropState: GraphDragDropState? = null,
    contextMenu: GraphContextMenuBinding? = null,
) {
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val accent = chipColor(chip.kind)
    val label = chip.refName ?: strings.graph.head

    BasicText(
        text = label,
        modifier = modifier
            .testTag(GraphTags.chip(label))
            .contextMenuFor(chip, contextMenu)
            .graphDragSource(dragDropState) { chip.dragSource() }
            .graphDropTarget(dragDropState) { chip.dropTarget() }
            .clip(RoundedCornerShape(shape.cornerSmall))
            // 칩 색을 옅게 깐 배경 + 같은 색 경계. 외곽선만 두면 칩이 배경에 묻혀 참조가 눈에 안 띈다.
            .background(accent.copy(alpha = CHIP_FILL_ALPHA))
            .border(shape.borderThin, accent, RoundedCornerShape(shape.cornerSmall))
            .padding(horizontal = spacing.extraSmall),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = UndineTokens.typography.caption.copy(color = accent),
    )
}

/** 배선이 없으면 아무 것도 얹지 않는다 — 칩 자체는 메뉴 없이도 그려져야 한다. */
@Composable
private fun Modifier.contextMenuFor(chip: GraphRefChip, contextMenu: GraphContextMenuBinding?): Modifier =
    if (contextMenu == null) {
        this
    } else {
        contextMenuTrigger { position -> contextMenu.state.open(chip.contextTarget(), position) }
    }

/**
 * 이 칩이 지목하는 조작 대상.
 *
 * HEAD 칩은 **커밋**이다 — HEAD 는 옮길 참조가 아니라 지금 어디에 있는지의 표시이고, 그 위치로
 * 할 수 있는 것은 커밋 대상 조작이다.
 */
private fun GraphRefChip.contextTarget(): GraphContextTarget = when (kind) {
    GraphRefKind.BRANCH -> GraphContextTarget.Branch(
        name = RefName(requireNotNull(refName)),
        head = requireNotNull(target),
        isRemote = isRemote,
    )

    GraphRefKind.TAG -> GraphContextTarget.Tag(
        name = RefName(requireNotNull(refName)),
        head = requireNotNull(target),
        isAnnotated = isAnnotated,
    )

    GraphRefKind.HEAD -> GraphContextTarget.Commit(requireNotNull(target))
}

private fun GraphRefChip.dragSource(): GraphDragSource = when (kind) {
    GraphRefKind.BRANCH -> GraphDragSource.Branch(RefName(requireNotNull(refName)), requireNotNull(target))
    GraphRefKind.TAG -> GraphDragSource.Tag(RefName(requireNotNull(refName)), requireNotNull(target), isAnnotated)
    GraphRefKind.HEAD -> GraphDragSource.Commit(requireNotNull(target))
}

private fun GraphRefChip.dropTarget(): GraphDropTarget = when (kind) {
    GraphRefKind.BRANCH -> GraphDropTarget.Branch(RefName(requireNotNull(refName)), requireNotNull(target))
    GraphRefKind.HEAD,
    GraphRefKind.TAG,
    -> GraphDropTarget.Commit(requireNotNull(target))
}

@Composable
private fun chipColor(kind: GraphRefKind): Color = when (kind) {
    GraphRefKind.HEAD -> UndineTokens.color.accent
    GraphRefKind.BRANCH -> UndineTokens.color.foregroundSecondary
    GraphRefKind.TAG -> UndineTokens.color.warning
}
