package dev.undine.presentation.graph

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.undine.domain.graph.LaneEdge

private const val LANE_CENTER_RATIO = 0.5f
private const val MINIMUM_LANE_COLUMNS = 1

/**
 * 레인 인덱스 → 픽셀 좌표 변환. **도메인은 레인 인덱스까지만 다루고 픽셀은 여기서 정한다**
 * ([dev.undine.domain.graph.GraphRow] 참조).
 *
 * 렌더링 없이 검증할 수 있도록 Composable 이 아닌 순수 함수로 둔다.
 */
object GraphLaneGeometry {

    /** 커밋 한 행의 높이. 노드가 세로 가운데에 놓이고 위아래로 선이 이어진다. */
    val ROW_HEIGHT: Dp = 28.dp

    /** 레인 한 칸의 폭. 그래프 열 전체 폭은 [columnWidth] 가 레인 수로 정한다. */
    val LANE_WIDTH: Dp = 14.dp

    /** 레인 [lane] 의 중심 x. [laneWidthPx] 는 레인 한 칸의 픽셀 폭이다. */
    fun laneCenterX(lane: Int, laneWidthPx: Float): Float = laneWidthPx * (lane + LANE_CENTER_RATIO)

    /**
     * 레인 [laneCount] 개를 담는 그래프 열의 폭.
     *
     * 레인이 하나도 없어도 한 칸은 남긴다 — 빈 이력에서 열 폭이 0 이 되면 커밋이 생겼을 때
     * 목록 전체가 가로로 튄다.
     */
    fun columnWidth(laneCount: Int): Dp = LANE_WIDTH * maxOf(laneCount, MINIMUM_LANE_COLUMNS)

    /**
     * 이 연결을 그릴 수 있는가. 부모가 현재 페이지에 없으면 이을 레인이 없으므로 그리지 않는다 —
     * **항목 자체는 제거하지 않는다.** `GraphRow.parents` 는 `Commit.parents` 와 1:1 로 대응한다.
     */
    fun isDrawable(edge: LaneEdge): Boolean = edge.toLane != LaneEdge.NO_LANE

    /**
     * 색 슬롯에 대응하는 레인 색. 슬롯은 팔레트 크기를 넘어가면 순환한다 —
     * 슬롯 수가 팔레트보다 많을 수 있다는 [dev.undine.domain.graph.GraphLaneAssigner] 계약과 같다.
     */
    fun laneColor(palette: List<Color>, colorSlot: Int): Color = palette[colorSlot % palette.size]
}
