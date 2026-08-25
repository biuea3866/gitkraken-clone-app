package dev.undine.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.shell
import dev.undine.presentation.i18n.strings

/**
 * 앱 창의 골격 — 툴바(상단 전체) · 사이드바(좌) · 중앙 그래프(우상) · 상세/diff(우하) 3분할.
 *
 * **각 영역은 [AppShellSlots] 의 슬롯이다.** 실제 화면은 UND-13~20 이 만들고 연결은 UND-26 이 한다.
 * 셸은 Gateway·UseCase 를 알지 못하고, 슬롯에 넘기는 것은 [AppShellState] 의 **읽기 전용 스냅샷**뿐이다
 * (compose-ui 규칙 1, 상태 끌어올리기).
 *
 * 슬롯을 하나도 채우지 않아도 레이아웃은 그대로 그려진다 — 배선 전 단계에서 셸만 띄워볼 수 있어야 한다.
 *
 * 분할 비율은 [splitState] 가 세션 동안만 들고 있다. `WindowBounds` 계약에 비율 필드가 없어 영속화는
 * 범위 밖이다 ([shellWindowStateFor] 참조).
 */
@Composable
fun AppShell(
    state: AppShellState,
    modifier: Modifier = Modifier,
    splitState: ShellSplitState = rememberShellSplitState(),
    slots: AppShellSlots = AppShellSlots(),
) {
    val selection = state.selection

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .testTag(ShellTags.ROOT),
    ) {
        Box(modifier = Modifier.fillMaxWidth().testTag(ShellTags.TOOLBAR)) {
            slots.toolbar(selection)
        }
        slots.tabs()
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ShellSplitArea(
                splitState = splitState,
                availableWidth = maxWidth,
                availableHeight = maxHeight,
                selection = selection,
                slots = slots,
            )
        }
    }
}

/**
 * 툴바를 뺀 나머지 영역의 3분할. 분할 비율의 기준 길이는 **이 영역**이라 툴바 높이가 섞이지 않는다.
 *
 * 중앙은 `weight(1f)` 로 **남은 만큼**을 받는다 — 그래서 최소 크기 판정에
 * [ShellSplitDefaults.SPLITTER_THICKNESS] 를 함께 넘겨야 중앙이 최소 크기보다 두께만큼 작아지지 않는다.
 */
@Composable
private fun ShellSplitArea(
    splitState: ShellSplitState,
    availableWidth: Dp,
    availableHeight: Dp,
    selection: AppShellSelection,
    slots: AppShellSlots,
) {
    val splitterThickness = ShellSplitDefaults.SPLITTER_THICKNESS

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .width(splitState.sidebarWidth(availableWidth, splitterThickness))
                .fillMaxHeight()
                .testTag(ShellTags.SIDEBAR),
        ) {
            slots.sidebar(selection)
        }
        ShellSplitter(
            orientation = ShellSplitterOrientation.VERTICAL,
            label = strings.shell.sidebarSplitter,
            testTag = ShellTags.SIDEBAR_SPLITTER,
            onResize = { delta -> splitState.resizeSidebarBy(delta, availableWidth, splitterThickness) },
        )
        Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag(ShellTags.CENTER)) {
                slots.center(selection)
            }
            ShellSplitter(
                orientation = ShellSplitterOrientation.HORIZONTAL,
                label = strings.shell.bottomSplitter,
                testTag = ShellTags.BOTTOM_SPLITTER,
                onResize = { delta -> splitState.resizeBottomBy(delta, availableHeight, splitterThickness) },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(splitState.bottomHeight(availableHeight, splitterThickness))
                    .testTag(ShellTags.BOTTOM),
            ) {
                slots.bottom(selection)
            }
        }
    }
}
