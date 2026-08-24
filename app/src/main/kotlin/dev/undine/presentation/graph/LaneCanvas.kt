package dev.undine.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import dev.undine.domain.graph.GraphRow
import dev.undine.domain.graph.LaneEdge
import dev.undine.presentation.design.UndineTokens

private val LANE_STROKE = 2.dp
private val NODE_RADIUS = 4.dp

/** 부모로 내려가는 선이 대각선을 끝내는 지점. 나머지 구간은 수직이라 아래 행과 정확히 이어진다. */
private const val EDGE_BEND_RATIO = 0.6f

private const val HALF = 2f

/**
 * 커밋 한 행의 레인 그림 — 통과선·커밋 노드·부모 연결선.
 *
 * 좌표 변환은 [GraphLaneGeometry] 가 소유한다. 행마다 폭이 흔들리지 않도록 [laneCount] 는
 * 목록 전체 기준값을 받는다.
 *
 * 부모 연결선은 커밋 노드에서 대각선으로 목표 레인까지 간 뒤 **수직으로 행 아래 끝까지** 내려간다.
 * 대각선만으로 바닥에 닿게 하면 다음 행의 선과 x 가 어긋난다.
 *
 * 연결선 색은 이 행의 색 슬롯을 쓴다. `LaneEdge` 는 목표 레인의 색 슬롯을 담지 않고,
 * 그 계약을 넓히는 것은 이 티켓 소유가 아니다 (wave 3 결정 A4).
 */
@Composable
internal fun LaneCanvas(
    row: GraphRow,
    laneCount: Int,
    modifier: Modifier = Modifier,
) {
    val palette = UndineTokens.color.lanePalette
    val nodeFill = UndineTokens.color.background

    Canvas(modifier = modifier) {
        val laneWidth = size.width / maxOf(laneCount, 1)
        val stroke = LANE_STROKE.toPx()
        val centerY = size.height / HALF
        val commitX = GraphLaneGeometry.laneCenterX(row.lane, laneWidth)
        val commitColor = GraphLaneGeometry.laneColor(palette, row.colorSlot)

        row.passThrough.forEach { segment ->
            val x = GraphLaneGeometry.laneCenterX(segment.lane, laneWidth)
            drawLine(
                color = GraphLaneGeometry.laneColor(palette, segment.colorSlot),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = stroke,
            )
        }

        // 위쪽(더 최신 커밋)에서 내려오는 선은 노드까지만 온다.
        drawLine(commitColor, Offset(commitX, 0f), Offset(commitX, centerY), stroke)

        row.parents
            .filter(GraphLaneGeometry::isDrawable)
            .forEach { drawParentEdge(it, laneWidth, stroke, commitColor) }

        drawCircle(commitColor, radius = NODE_RADIUS.toPx(), center = Offset(commitX, centerY))
        drawCircle(nodeFill, radius = NODE_RADIUS.toPx() - stroke, center = Offset(commitX, centerY))
    }
}

private fun DrawScope.drawParentEdge(
    edge: LaneEdge,
    laneWidth: Float,
    stroke: Float,
    color: Color,
) {
    val centerY = size.height / HALF
    val fromX = GraphLaneGeometry.laneCenterX(edge.fromLane, laneWidth)
    val toX = GraphLaneGeometry.laneCenterX(edge.toLane, laneWidth)
    val bendY = centerY + (size.height - centerY) * EDGE_BEND_RATIO

    drawLine(color, Offset(fromX, centerY), Offset(toX, bendY), stroke)
    drawLine(color, Offset(toX, bendY), Offset(toX, size.height), stroke)
}
