package dev.undine.presentation.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.undine.domain.WindowBounds

/** 저장값이 쓸 수 없을 때 쓰는 창 크기. */
val DEFAULT_WINDOW_SIZE: DpSize = DpSize(1280.dp, 800.dp)

/**
 * 셸 3분할이 최소 크기를 지키며 들어갈 수 있는 최소 창 크기.
 *
 * **각 축의 분할선 두께를 함께 더한다** — 분할선도 축 길이를 실제로 점유하므로 빼먹으면
 * 남은 폭·높이를 받는 중앙 영역이 최소 크기보다 두께만큼 작아진다 ([clampPaneFraction]).
 * 세로축은 [AppShell] 의 툴바 슬롯과 OS 창 장식이 이 위에 더 얹히지만, 둘 다 셸이 크기를 모르는
 * 값이라 창을 배선하는 UND-26 이 자기 툴바 높이를 더해 쓴다.
 */
val MIN_WINDOW_WIDTH: Dp = ShellSplitDefaults.SIDEBAR_MIN_WIDTH +
    ShellSplitDefaults.SPLITTER_THICKNESS +
    ShellSplitDefaults.CENTER_MIN_WIDTH
val MIN_WINDOW_HEIGHT: Dp = ShellSplitDefaults.CENTER_MIN_HEIGHT +
    ShellSplitDefaults.SPLITTER_THICKNESS +
    ShellSplitDefaults.BOTTOM_MIN_HEIGHT

/**
 * 저장된 [WindowBounds] 로 창 상태를 만든다. `main()` 에서의 사용은 UND-26 이 배선한다.
 *
 * **복원 대상은 width·height·maximized 세 값뿐이다.** 창 위치(x·y)는 현재 `WindowBounds` 계약에
 * 없으므로 [WindowPosition.PlatformDefault] 로 둔다 — 위치를 넣게 되면 저장된 위치가 화면 밖일 때
 * 기본 위치로 되돌리는 가드를 함께 넣어야 한다(외장 모니터를 뺀 뒤 보이지 않는 창으로 뜨는 사고).
 * 분할 비율도 계약에 없어 저장·복원하지 않는다 ([ShellSplitState]).
 *
 * 저장값이 0 이하면 보이지 않는 창이 되므로 [DEFAULT_WINDOW_SIZE] 로, 최소 크기보다 작으면
 * 최소 크기로 올린다. 최대화 상태여도 크기 값은 그대로 들고 있다 — 창을 되돌릴 때 쓰인다.
 */
fun shellWindowStateFor(bounds: WindowBounds): WindowState = WindowState(
    placement = if (bounds.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
    position = WindowPosition.PlatformDefault,
    size = restoredWindowSize(bounds),
)

private fun restoredWindowSize(bounds: WindowBounds): DpSize {
    if (bounds.width <= 0 || bounds.height <= 0) return DEFAULT_WINDOW_SIZE
    return DpSize(
        width = bounds.width.dp.coerceAtLeast(MIN_WINDOW_WIDTH),
        height = bounds.height.dp.coerceAtLeast(MIN_WINDOW_HEIGHT),
    )
}
