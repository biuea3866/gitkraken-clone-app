package dev.undine.presentation.contextmenu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.ContextMenuStrings
import dev.undine.presentation.i18n.contextMenu
import dev.undine.presentation.i18n.strings
import kotlin.math.roundToInt

/** 메뉴 최소 폭. 항목 문구가 대상과의 관계까지 담아 한 줄로 읽히게 한다. */
private val MENU_MIN_WIDTH = 200.dp

/**
 * 커서 위치에 뜨는 그래프 조작 메뉴.
 *
 * 그래프 화면 위에 얹히는 오버레이다 — 사이드바는 행 아래 인라인으로 펼치고 그래프는 커서 팝업으로
 * 띄운다. 한 화면 안에서 두 방식이 섞이지 않게 각 표면의 기존 패턴을 그대로 따른다 (결정 D15).
 *
 * **메뉴 밖 클릭은 메뉴만 닫는다.** 아래를 덮는 영역이 누름을 소비하므로 그 클릭이 커밋 행 선택으로
 * 새지 않는다. ESC 도 아무것도 실행하지 않고 닫는다 (결정 D7).
 */
@Composable
internal fun GraphContextMenuOverlay(binding: GraphContextMenuBinding, modifier: Modifier = Modifier) {
    val request = binding.state.request ?: return
    val entries = graphMenuEntriesFor(request.target, binding.selection())
    // 오버레이 자신의 루트 좌표. 커서 좌표는 루트 기준이라, 이 값을 빼야 오버레이 안의 위치가 된다.
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> origin = coordinates.positionInRoot() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    binding.state.close()
                    true
                } else {
                    false
                }
            },
    ) {
        MenuDismissArea(onDismiss = binding.state::close)
        MenuLayer(cursor = request.position - origin) {
            MenuItems(
                entries = entries,
                onSelect = { operation ->
                    binding.onOperation(operation)
                    binding.state.close()
                },
            )
        }
    }
}

/**
 * 메뉴 밖을 덮는 영역. 누름을 [PointerEventPass.Initial] 에서 소비해 **아래 행으로 전달되지 않게**
 * 한다 — 전달되면 메뉴를 닫으려던 클릭이 다른 커밋을 고른다.
 *
 * 메뉴 항목은 이 영역의 **형제**로 위에 놓이므로 항목 클릭은 여기로 오지 않는다.
 */
@Composable
private fun MenuDismissArea(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(ContextMenuTags.SCRIM)
            .pointerInput(onDismiss) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        event.changes.forEach { change -> change.consume() }
                        onDismiss()
                    }
                }
            },
    )
}

@Composable
private fun MenuItems(
    entries: List<GraphMenuEntry>,
    onSelect: (GraphOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing
    val copy = strings.contextMenu
    val menuShape = RoundedCornerShape(shape.cornerSmall)

    Column(
        modifier = modifier
            .widthIn(min = MENU_MIN_WIDTH)
            .background(colors.surface, menuShape)
            .border(shape.borderThin, colors.border, menuShape)
            .padding(spacing.small)
            .semantics { contentDescription = copy.menuName }
            .testTag(ContextMenuTags.MENU),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        entries.forEach { entry -> MenuItem(entry = entry, copy = copy, onSelect = onSelect) }
    }
}

/**
 * 항목 하나. **막힌 항목도 사라지지 않고 사유와 함께 남는다** — 사라지면 사용자는 그 조작이
 * 존재하지 않는다고 읽는다 (결정 D3). 비활성 항목은 눌러도 실행 경로로 가지 않는다.
 */
@Composable
private fun MenuItem(
    entry: GraphMenuEntry,
    copy: ContextMenuStrings,
    onSelect: (GraphOperation) -> Unit,
) {
    val kind = entry.operation.kind()
    val label = copy.labelOf(kind)
    val text = entry.blockedReason?.let { reason -> copy.blockedItem(label, copy.reason(reason)) } ?: label

    UndineToolbarButton(
        label = text,
        onClick = { onSelect(entry.operation) },
        modifier = Modifier
            .semantics { contentDescription = text }
            .testTag(ContextMenuTags.item(kind)),
        enabled = entry.enabled,
    )
}

/** 항목 이름은 카탈로그에서만 읽는다 — 화면에 문자열을 두지 않는다 (결정 D5). */
private fun ContextMenuStrings.labelOf(kind: GraphOperationKind): String = when (kind) {
    GraphOperationKind.MERGE -> itemMerge
    GraphOperationKind.REBASE -> itemRebase
    GraphOperationKind.CHERRY_PICK -> itemCherryPick
    GraphOperationKind.RESET_BRANCH -> itemReset
    GraphOperationKind.MOVE_TAG -> itemMoveTag
}

/**
 * 커서 자리에 메뉴를 놓되 **컨테이너 안으로 잘라 넣는다.**
 *
 * 그대로 놓으면 오른쪽·아래 가장자리에서 클릭한 메뉴가 화면 밖으로 나가 항목을 누를 수 없다 —
 * 사용자에게는 우클릭이 아무 일도 하지 않은 것으로 보인다. 자기 크기를 알아야 자를 수 있어
 * `offset` 이 아니라 [Layout] 으로 배치한다.
 */
@Composable
private fun MenuLayer(cursor: Offset, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val menu = measurables.single().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            menu.place(
                x = cursor.x.roundToInt().coerceIn(0, (constraints.maxWidth - menu.width).coerceAtLeast(0)),
                y = cursor.y.roundToInt().coerceIn(0, (constraints.maxHeight - menu.height).coerceAtLeast(0)),
            )
        }
    }
}
