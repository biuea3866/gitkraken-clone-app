package dev.undine.presentation.graph

import androidx.compose.ui.unit.dp
import dev.undine.domain.graph.EdgeKind
import dev.undine.domain.graph.LaneEdge
import dev.undine.presentation.design.ColorTokens
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val LANE_WIDTH_PX = 20f
private const val TOLERANCE = 0.001f

/** 레인 인덱스 → 픽셀 좌표 변환. 렌더러가 소유하는 계산이라 렌더링 없이 검증한다. */
class GraphLaneGeometrySpec : FunSpec({

    test("레인 중심 x 는 레인 폭의 절반에서 시작해 레인마다 한 폭씩 오른쪽으로 간다") {
        GraphLaneGeometry.laneCenterX(0, LANE_WIDTH_PX) shouldBe (10f plusOrMinus TOLERANCE)
        GraphLaneGeometry.laneCenterX(1, LANE_WIDTH_PX) shouldBe (30f plusOrMinus TOLERANCE)
        GraphLaneGeometry.laneCenterX(3, LANE_WIDTH_PX) shouldBe (70f plusOrMinus TOLERANCE)
    }

    test("그래프 열 폭은 레인 수에 비례하고 레인이 없어도 한 레인 폭은 남는다") {
        GraphLaneGeometry.columnWidth(0) shouldBe GraphLaneGeometry.LANE_WIDTH
        GraphLaneGeometry.columnWidth(1) shouldBe GraphLaneGeometry.LANE_WIDTH
        GraphLaneGeometry.columnWidth(3) shouldBe GraphLaneGeometry.LANE_WIDTH * 3
    }

    test("페이지 밖 부모로 이어지는 연결은 그리지 않는다") {
        val offPage = LaneEdge(fromLane = 0, toLane = LaneEdge.NO_LANE, kind = EdgeKind.STRAIGHT)
        val drawable = LaneEdge(fromLane = 0, toLane = 1, kind = EdgeKind.MERGE)

        GraphLaneGeometry.isDrawable(offPage) shouldBe false
        GraphLaneGeometry.isDrawable(drawable) shouldBe true
    }

    test("색 슬롯은 팔레트 크기를 넘어가면 순환한다") {
        val palette = ColorTokens.Light.lanePalette

        GraphLaneGeometry.laneColor(palette, 0) shouldBe palette[0]
        GraphLaneGeometry.laneColor(palette, palette.lastIndex) shouldBe palette[palette.lastIndex]
        GraphLaneGeometry.laneColor(palette, palette.size) shouldBe palette[0]
        GraphLaneGeometry.laneColor(palette, palette.size + 2) shouldBe palette[2]
    }

    test("행 높이와 레인 폭은 0 보다 큰 고정값이다") {
        (GraphLaneGeometry.ROW_HEIGHT > 0.dp) shouldBe true
        (GraphLaneGeometry.LANE_WIDTH > 0.dp) shouldBe true
    }
})
