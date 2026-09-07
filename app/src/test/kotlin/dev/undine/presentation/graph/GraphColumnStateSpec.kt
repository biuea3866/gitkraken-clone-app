package dev.undine.presentation.graph

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

private val LANE = GraphLaneGeometry.LANE_WIDTH

/** dp 계산은 부동소수라 마지막 자리를 비교하지 않는다 — 0.01dp 는 픽셀보다 작다. */
private const val DP_TOLERANCE = 0.01

private infix fun Dp.shouldBeDp(expected: Dp) =
    value.toDouble() shouldBe (expected.value.toDouble() plusOrMinus DP_TOLERANCE)

/**
 * 그래프 열의 표시 폭 계산 — **렌더링 없이** 순수 값으로 검증한다 (결정 A6).
 *
 * 상한(비율)·내용 최소(dp)·조절 요청값의 클램프가 여기서 닫히면 화면 테스트는 배치와 조작 경로만
 * 확인하면 된다.
 */
class GraphColumnStateSpec : FunSpec({

    test("공유 폭은 분할선 두께와 내용 영역 좌우 여백을 뺀 값이다") {
        GraphColumnDefaults.sharedWidth(
            totalWidth = 1000.dp,
            splitterThickness = 4.dp,
            contentPadding = 12.dp,
        ) shouldBeDp 972.dp
    }

    test("공유 폭은 음수가 되지 않는다") {
        GraphColumnDefaults.sharedWidth(
            totalWidth = 10.dp,
            splitterThickness = 4.dp,
            contentPadding = 12.dp,
        ) shouldBeDp 0.dp
    }

    test("레인이 적으면 상한에 닿지 않고 레인 수에 비례한 폭을 쓴다") {
        GraphColumnDefaults.columnWidth(sharedWidth = 1000.dp, laneCount = 3, requestedWidth = null) shouldBeDp
            GraphLaneGeometry.columnWidth(3)
    }

    test("레인이 많으면 공유 폭의 40% 에서 멈춘다") {
        GraphColumnDefaults.columnWidth(sharedWidth = 1000.dp, laneCount = 100, requestedWidth = null) shouldBeDp
            400.dp
    }

    test("공유 폭이 좁으면 내용 영역 240dp 최소가 비율보다 먼저 걸린다") {
        // 320dp 는 셸 중앙 영역의 최소 폭이다 — 40%(128dp) 가 아니라 320-240=80dp 가 이긴다.
        GraphColumnDefaults.columnWidth(sharedWidth = 320.dp, laneCount = 100, requestedWidth = null) shouldBeDp
            80.dp
    }

    test("어떤 공유 폭에서도 그래프 열은 한 레인 폭 이상이다") {
        listOf((-50).dp, 0.dp, 10.dp, LANE, 240.dp, 320.dp, 1000.dp).forEach { shared ->
            GraphColumnDefaults.columnWidth(shared, laneCount = 100, requestedWidth = null) shouldBeGreaterThanOrEqualTo
                LANE
        }
    }

    test("공유 폭이 240dp 이하면 그래프는 한 레인만 남기고 나머지를 내용 영역에 넘긴다") {
        GraphColumnDefaults.columnWidth(sharedWidth = 240.dp, laneCount = 100, requestedWidth = null) shouldBeDp LANE
        GraphColumnDefaults.columnWidth(sharedWidth = 200.dp, laneCount = 100, requestedWidth = null) shouldBeDp LANE
        GraphColumnDefaults.columnWidth(sharedWidth = 0.dp, laneCount = 100, requestedWidth = null) shouldBeDp LANE
        GraphColumnDefaults.columnWidth(sharedWidth = (-100).dp, laneCount = 1, requestedWidth = null) shouldBeDp LANE
    }

    test("레인이 하나도 없어도 그래프 열은 한 레인 폭을 유지한다") {
        GraphColumnDefaults.columnWidth(sharedWidth = 1000.dp, laneCount = 0, requestedWidth = null) shouldBeDp LANE
    }

    test("조절 요청값은 한 레인 폭과 현재 상한 사이로 클램프된다") {
        GraphColumnDefaults.columnWidth(1000.dp, laneCount = 100, requestedWidth = 200.dp) shouldBeDp 200.dp
        GraphColumnDefaults.columnWidth(1000.dp, laneCount = 100, requestedWidth = 900.dp) shouldBeDp 400.dp
        GraphColumnDefaults.columnWidth(1000.dp, laneCount = 100, requestedWidth = 0.dp) shouldBeDp LANE
        GraphColumnDefaults.columnWidth(1000.dp, laneCount = 100, requestedWidth = (-30).dp) shouldBeDp LANE
    }

    test("표시 폭에 들어가는 레인만 세고 나머지는 숨은 레인이 된다") {
        val layout = GraphColumnDefaults.layout(sharedWidth = 1000.dp, laneCount = 100, requestedWidth = null)

        layout.width shouldBeDp 400.dp
        layout.visibleLaneCount shouldBe (400.dp / LANE).toInt()
        layout.hiddenLaneCount shouldBe 100 - layout.visibleLaneCount
        layout.hiddenLaneCount shouldBeGreaterThan 0
        layout.isLaneVisible(0) shouldBe true
        layout.isLaneVisible(layout.visibleLaneCount) shouldBe false
    }

    test("표시 폭 안에 다 들어가면 숨은 레인이 없다") {
        val layout = GraphColumnDefaults.layout(sharedWidth = 1000.dp, laneCount = 3, requestedWidth = null)

        layout.visibleLaneCount shouldBe 3
        layout.hiddenLaneCount shouldBe 0
        layout.isLaneVisible(2) shouldBe true
    }

    test("레인이 0 개라도 그릴 레인 수는 한 칸으로 센다") {
        val layout = GraphColumnDefaults.layout(sharedWidth = 1000.dp, laneCount = 0, requestedWidth = null)

        layout.visibleLaneCount shouldBe 1
        layout.hiddenLaneCount shouldBe 0
    }

    test("조절하지 않은 상태는 레인 요구 폭과 상한 중 작은 값을 쓴다") {
        val state = GraphColumnState()

        state.requestedWidth shouldBe null
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 3) shouldBeDp GraphLaneGeometry.columnWidth(3)
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 100) shouldBeDp 400.dp
    }

    test("조절값은 화면을 줄여도 보존되고 다시 넓히면 그대로 돌아온다") {
        val state = GraphColumnState()

        state.resizeBy(delta = 100.dp, sharedWidth = 1000.dp, laneCount = 3)
        val requested = state.displayWidth(1000.dp, laneCount = 3)
        requested shouldBeDp (GraphLaneGeometry.columnWidth(3) + 100.dp)

        // 창을 줄이면 표시값만 상한으로 클램프된다.
        state.displayWidth(sharedWidth = 320.dp, laneCount = 3) shouldBeDp 80.dp

        // 다시 넓히면 사용자가 요청한 폭이 되살아난다 — 조작이 조용히 사라지지 않는다.
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 3) shouldBeDp requested
    }

    test("조절값을 상한 밖으로 요청해도 표시 폭이 상한을 넘지 않는다") {
        val state = GraphColumnState()

        state.resizeBy(delta = 5000.dp, sharedWidth = 1000.dp, laneCount = 100)

        state.displayWidth(1000.dp, laneCount = 100) shouldBeDp 400.dp
    }

    test("조절값은 한 레인 폭 아래로 내려가지 않는다") {
        val state = GraphColumnState()

        state.resizeBy(delta = (-5000).dp, sharedWidth = 1000.dp, laneCount = 100)

        state.displayWidth(1000.dp, laneCount = 100) shouldBeDp LANE
    }

    test("페이지가 늘어 레인 수가 커져도 표시 폭은 현재 상한을 넘지 않는다") {
        val state = GraphColumnState()

        state.displayWidth(sharedWidth = 1000.dp, laneCount = 3) shouldBeDp GraphLaneGeometry.columnWidth(3)
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 28) shouldBeDp GraphLaneGeometry.columnWidth(28)
        // 29 레인은 406dp 를 요구하지만 상한 400dp 에서 멈춘다.
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 29) shouldBeDp 400.dp
        state.displayWidth(sharedWidth = 1000.dp, laneCount = 200) shouldBeDp 400.dp
    }

    test("기본 상한과 내용 최소는 한 곳에서 나온다") {
        GraphColumnDefaults.MAX_GRAPH_FRACTION shouldBe 0.4f
        GraphColumnDefaults.CONTENT_MIN_WIDTH shouldBeDp 240.dp
    }
})
