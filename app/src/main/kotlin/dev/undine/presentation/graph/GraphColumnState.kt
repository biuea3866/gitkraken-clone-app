package dev.undine.presentation.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * 그래프 열의 표시 폭 기본값 — **상한과 내용 최소의 단일 출처**다.
 *
 * 그리는 쪽(레인 캔버스)·판정하는 쪽(잘림 표시)·조절 한계를 정하는 쪽이 같은 값을 봐야
 * 내용 영역이 최소보다 작아지지 않는다 ([dev.undine.presentation.shell.ShellSplitDefaults]
 * `SPLITTER_THICKNESS` 와 같은 이유).
 *
 * [dev.undine.presentation.graph.GraphViewState.laneCount] 와
 * [GraphLaneGeometry.columnWidth] 의 비례식은 **건드리지 않는다** — 상한은 그리는 시점에서만 건다.
 * 기하 계산을 고치면 스크롤 중 열 폭이 출렁인다.
 *
 * 렌더링 없이 검증할 수 있도록 Composable 이 아닌 순수 함수로 둔다.
 */
object GraphColumnDefaults {

    /** 그래프 열이 가져갈 수 있는 공유 폭의 최대 비율. */
    const val MAX_GRAPH_FRACTION: Float = 0.4f

    /**
     * 배지·메시지·작성자·시각·해시가 나눠 쓰는 내용 영역의 최소 폭.
     *
     * 최소는 **비율이 아니라 dp** 다 — 창을 줄였을 때 비율만 지키면 메시지가 읽을 수 없게 찌그러진다.
     */
    val CONTENT_MIN_WIDTH: Dp = 240.dp

    /**
     * 그래프 열과 내용 영역이 **실제로 나눠 갖는** 폭.
     *
     * 분할선과 내용 영역의 좌우 여백은 둘 중 누구의 몫도 아니므로 먼저 떼어 낸다. 판정하는 쪽과
     * 배치하는 쪽이 다른 분모를 보면 내용 영역이 그 차이만큼 최소보다 작아진다.
     */
    fun sharedWidth(totalWidth: Dp, splitterThickness: Dp, contentPadding: Dp): Dp =
        (totalWidth - splitterThickness - contentPadding * 2).coerceAtLeast(0.dp)

    /**
     * 지금 폭에서 그래프 열이 넓어질 수 있는 한계 — 비율 상한과 내용 최소 중 **먼저 걸리는 쪽**이다.
     *
     * 둘을 동시에 만족시킬 수 없을 만큼 [sharedWidth] 가 좁으면 한 레인 폭을 돌려준다. 내용 영역이
     * 최소를 못 지키는 것과 그래프 열을 0 으로 만드는 것은 다르다 — 폭이 0 이면 다시 끌어낼 손잡이를
     * 잡을 자리가 사라져 되돌릴 수 없는 조작이 된다.
     */
    fun maxColumnWidth(sharedWidth: Dp): Dp =
        minOf(sharedWidth * MAX_GRAPH_FRACTION, sharedWidth - CONTENT_MIN_WIDTH)
            .coerceAtLeast(GraphLaneGeometry.LANE_WIDTH)

    /**
     * 실제로 그릴 그래프 열의 폭.
     *
     * @param requestedWidth 사용자가 조절해 요청한 폭. 조절한 적이 없으면 `null` 이고, 그때는 레인이
     *   요구하는 폭([GraphLaneGeometry.columnWidth])과 현재 상한 중 작은 쪽을 쓴다.
     */
    fun columnWidth(sharedWidth: Dp, laneCount: Int, requestedWidth: Dp?): Dp {
        val desired = requestedWidth ?: GraphLaneGeometry.columnWidth(laneCount)
        return desired.coerceIn(GraphLaneGeometry.LANE_WIDTH, maxColumnWidth(sharedWidth))
    }

    /** 이번 컴포지션이 쓸 열 배치. 열 수준 표시와 행 수준 표시가 같은 값을 본다. */
    fun layout(sharedWidth: Dp, laneCount: Int, requestedWidth: Dp?): GraphColumnLayout =
        GraphColumnLayout(width = columnWidth(sharedWidth, laneCount, requestedWidth), laneCount = laneCount)
}

/**
 * 정해진 그래프 열의 배치 — 표시 폭과 그 폭에 들어가는 레인 수.
 *
 * 레인은 [GraphLaneGeometry.LANE_WIDTH] **고정 폭**으로 그린다. 폭을 레인 수로 나누면 상한이 걸린
 * 순간 레인 29개가 좁은 폭에 뭉개져, "잘라서 그린다" 가 아니라 "다 그리되 읽을 수 없게 만든다" 가 된다.
 */
data class GraphColumnLayout(val width: Dp, val laneCount: Int) {

    /** 표시 폭 안에 온전히 들어가는 레인 수. 열 폭은 한 레인 이상이므로 최소 한 칸은 그린다. */
    val visibleLaneCount: Int
        get() = maxOf(1, floor(width / GraphLaneGeometry.LANE_WIDTH).toInt())

    /** 표시 폭 밖으로 잘려 그리지 않는 레인 수. */
    val hiddenLaneCount: Int get() = (laneCount - visibleLaneCount).coerceAtLeast(0)

    fun isLaneVisible(lane: Int): Boolean = lane < visibleLaneCount
}

/**
 * 그래프 열 폭의 세션 상태 홀더.
 *
 * **영속화하지 않는다** — 설정 스키마 확장은 이 티켓 밖이다 (결정 A3).
 *
 * 조절 요청값은 **그대로 보관하고 그릴 때만 클램프**한다. 클램프한 값을 저장하면 창을 줄였다
 * 늘릴 때마다 사용자가 다시 끌어야 한다 — 조작이 조용히 사라지는 것과 같다.
 */
@Stable
class GraphColumnState(requestedWidth: Dp? = null) {

    private var requestedState by mutableStateOf(requestedWidth)

    /** 사용자가 요청한 폭. 조절한 적이 없으면 `null` 이다. */
    val requestedWidth: Dp? get() = requestedState

    /** 지금 공유 폭·레인 수에서 그릴 폭. 요청값은 여기서만 상한·하한으로 접힌다. */
    fun displayWidth(sharedWidth: Dp, laneCount: Int): Dp =
        GraphColumnDefaults.columnWidth(sharedWidth, laneCount, requestedState)

    /** 지금 그리는 배치. [displayWidth] 와 같은 값을 쓴다. */
    fun layout(sharedWidth: Dp, laneCount: Int): GraphColumnLayout =
        GraphColumnDefaults.layout(sharedWidth, laneCount, requestedState)

    /** 분할선을 [delta] 만큼 오른쪽으로 옮긴다 — 양수면 그래프 열이 넓어진다. */
    fun resizeBy(delta: Dp, sharedWidth: Dp, laneCount: Int) {
        requestedState = displayWidth(sharedWidth, laneCount) + delta
    }
}

/** 컴포지션 수명 동안 유지되는 그래프 열 폭. 영속화 대상이 아니므로 `remember` 로 충분하다. */
@Composable
fun rememberGraphColumnState(): GraphColumnState = remember { GraphColumnState() }
