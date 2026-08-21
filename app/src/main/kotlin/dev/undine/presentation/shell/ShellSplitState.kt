package dev.undine.presentation.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.undine.presentation.design.SpacingTokens

/**
 * 분할 기본값과 최소 크기. 값의 근거는 wave 3 결정 문서 §UND-12 다.
 *
 * 최소 크기는 **비율이 아니라 dp** 다 — 창을 줄였을 때 비율만 지키면 영역이 읽을 수 없게 찌그러진다.
 */
object ShellSplitDefaults {

    /** 사이드바 기본 폭 비율. */
    const val DEFAULT_SIDEBAR_FRACTION: Float = 0.22f

    /** 하단 상세/diff 기본 높이 비율(툴바를 뺀 영역 기준). */
    const val DEFAULT_BOTTOM_FRACTION: Float = 0.35f

    val SIDEBAR_MIN_WIDTH: Dp = 180.dp

    val CENTER_MIN_WIDTH: Dp = 320.dp

    /**
     * 중앙 그래프의 최소 높이. 결정 문서는 최소 크기를 영역별 한 값(중앙 320dp)으로 주었고
     * 세로 분할에도 같은 값을 적용한다 — 그래프가 한 화면에 몇 줄도 못 보이는 상태를 막는다.
     */
    val CENTER_MIN_HEIGHT: Dp = 320.dp

    val BOTTOM_MIN_HEIGHT: Dp = 120.dp

    /** 분할선에 포커스가 있을 때 방향키 한 번이 움직이는 거리. */
    val KEYBOARD_STEP: Dp = 8.dp

    /**
     * 분할선이 축에서 차지하는 두께 — **분할선을 그리는 쪽·최소 크기를 판정하는 쪽·창 최소 크기를
     * 계산하는 쪽의 단일 출처**다.
     *
     * 셋 중 하나라도 다른 값을 보면 남은 폭을 받는 중앙 영역이 최소 크기보다 그 차이만큼 작아진다.
     * 값 자체는 간격 토큰에서 온다 (compose-ui 규칙 5). Composable 밖에서 읽는 이유는
     * [MIN_WINDOW_WIDTH]·[MIN_WINDOW_HEIGHT] 가 **컴포지션 이전**(창을 만드는 시점)에 필요해서다 —
     * 간격 토큰은 `UndineTokenSet.Light`·`Dark` 가 같은 [SpacingTokens.Default] 를 쓰므로
     * 테마 모드와 무관하며, 컴포지션 안에서 읽어도 같은 값이다.
     */
    val SPLITTER_THICKNESS: Dp = SpacingTokens.Default.extraSmall
}

/**
 * 두 영역이 나눠 갖는 축에서 **추적 중인 영역**의 비율을 최소 크기 안으로 가둔다.
 *
 * @param requested 계산된 희망 비율. 드래그·키보드·창 크기 변화가 모두 이 함수를 거친다.
 * @param total 축 전체 길이. 0 이하면 비율을 판정할 수 없어 [requested] 를 그대로 돌려준다.
 * @param paneMin 추적 중인 영역의 최소 크기.
 * @param otherMin 반대편 영역의 최소 크기.
 * @param splitterThickness 두 영역 사이에 놓인 분할선의 두께. **축 길이에서 분할선이 먼저 떼어 간다** —
 *   빼지 않으면 반대편 영역의 실제 크기가 [otherMin] 보다 딱 이 두께만큼 작아진다.
 * @return 두 최소를 동시에 만족할 수 없을 만큼 [total] 이 작으면 **두 최소의 비율대로** 나눈 값.
 *   어느 한쪽을 0 으로 만들지 않기 위한 결정적 폴백이다.
 */
internal fun clampPaneFraction(
    requested: Float,
    total: Dp,
    paneMin: Dp,
    otherMin: Dp,
    splitterThickness: Dp,
): Float {
    if (total <= 0.dp) return requested
    val lower = (paneMin / total).coerceIn(0f, 1f)
    val upper = ((total - otherMin - splitterThickness) / total).coerceIn(0f, 1f)
    return if (lower > upper) paneMin / (paneMin + otherMin) else requested.coerceIn(lower, upper)
}

/**
 * 분할 비율을 보유하는 세션 상태 홀더.
 *
 * **영속화하지 않는다.** `Settings.window` 의 `WindowBounds` 계약에는 분할 비율 필드가 없고,
 * 스키마 확장은 `SettingsGateway` 를 소유한 티켓의 몫이다. 그때까지 비율은 앱 세션 동안만 산다.
 *
 * 드래그와 키보드는 같은 [resizeSidebarBy]·[resizeBottomBy] 를 거친다 — 조작 경로가 둘이어도
 * 최소 크기 규칙은 한 곳에서만 적용된다.
 */
@Stable
class ShellSplitState(
    sidebarFraction: Float = ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION,
    bottomFraction: Float = ShellSplitDefaults.DEFAULT_BOTTOM_FRACTION,
) {
    private var sidebarFractionState by mutableFloatStateOf(sidebarFraction)
    private var bottomFractionState by mutableFloatStateOf(bottomFraction)

    val sidebarFraction: Float get() = sidebarFractionState
    val bottomFraction: Float get() = bottomFractionState

    /**
     * 현재 폭에서의 사이드바 폭. 저장된 비율이 최소 크기를 어기면 최소 크기가 이긴다.
     *
     * @param splitterThickness 사이드바와 중앙 사이 분할선 두께. 중앙은 남은 폭을 받으므로
     *   이 값을 빼야 중앙 실제 폭이 [ShellSplitDefaults.CENTER_MIN_WIDTH] 이상으로 남는다.
     */
    fun sidebarWidth(totalWidth: Dp, splitterThickness: Dp): Dp = totalWidth * clampPaneFraction(
        requested = sidebarFractionState,
        total = totalWidth,
        paneMin = ShellSplitDefaults.SIDEBAR_MIN_WIDTH,
        otherMin = ShellSplitDefaults.CENTER_MIN_WIDTH,
        splitterThickness = splitterThickness,
    )

    /** 현재 높이에서의 하단 영역 높이. [splitterThickness] 의 역할은 [sidebarWidth] 와 같다. */
    fun bottomHeight(totalHeight: Dp, splitterThickness: Dp): Dp = totalHeight * clampPaneFraction(
        requested = bottomFractionState,
        total = totalHeight,
        paneMin = ShellSplitDefaults.BOTTOM_MIN_HEIGHT,
        otherMin = ShellSplitDefaults.CENTER_MIN_HEIGHT,
        splitterThickness = splitterThickness,
    )

    /** 세로 분할선을 [delta] 만큼 오른쪽으로 옮긴다 — 양수면 사이드바가 넓어진다. */
    fun resizeSidebarBy(delta: Dp, totalWidth: Dp, splitterThickness: Dp) {
        if (totalWidth <= 0.dp) return
        sidebarFractionState = clampPaneFraction(
            requested = (sidebarWidth(totalWidth, splitterThickness) + delta) / totalWidth,
            total = totalWidth,
            paneMin = ShellSplitDefaults.SIDEBAR_MIN_WIDTH,
            otherMin = ShellSplitDefaults.CENTER_MIN_WIDTH,
            splitterThickness = splitterThickness,
        )
    }

    /** 가로 분할선을 [delta] 만큼 아래로 옮긴다 — 양수면 하단 영역이 좁아진다. */
    fun resizeBottomBy(delta: Dp, totalHeight: Dp, splitterThickness: Dp) {
        if (totalHeight <= 0.dp) return
        bottomFractionState = clampPaneFraction(
            requested = (bottomHeight(totalHeight, splitterThickness) - delta) / totalHeight,
            total = totalHeight,
            paneMin = ShellSplitDefaults.BOTTOM_MIN_HEIGHT,
            otherMin = ShellSplitDefaults.CENTER_MIN_HEIGHT,
            splitterThickness = splitterThickness,
        )
    }
}

/** 컴포지션 수명 동안 유지되는 분할 상태. 영속화 대상이 아니므로 `remember` 로 충분하다. */
@Composable
fun rememberShellSplitState(): ShellSplitState = remember { ShellSplitState() }
