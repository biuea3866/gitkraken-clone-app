package dev.undine.presentation.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

private val TOTAL_WIDTH = 1000.dp
private val TOTAL_HEIGHT = 800.dp

/** 셸이 그리는 분할선 두께. 축 길이에서 분할선이 먼저 떼어 가므로 최소 크기 판정에 들어간다. */
private val SPLITTER = ShellSplitDefaults.SPLITTER_THICKNESS

/** dp 계산은 부동소수라 마지막 자리를 비교하지 않는다 — 0.01dp 는 픽셀보다 작다. */
private const val DP_TOLERANCE = 0.01

private infix fun Dp.shouldBeDp(expected: Dp) =
    value.toDouble() shouldBe (expected.value.toDouble() plusOrMinus DP_TOLERANCE)

/** 최소 크기 하한 검증. 비율 곱셈의 마지막 자리 오차는 최소 크기 위반이 아니다. */
private infix fun Dp.shouldBeAtLeastDp(minimum: Dp) =
    value.toDouble() shouldBeGreaterThanOrEqualTo (minimum.value.toDouble() - DP_TOLERANCE)

/**
 * 분할 비율 계산 — 렌더링 없이 순수 값으로 검증한다.
 * 최소 크기 경계와 키보드 단위가 여기서 닫히면 화면 테스트는 배치와 조작 경로만 확인하면 된다.
 */
class ShellSplitStateSpec : FunSpec({

    test("기본 비율은 사이드바 22% · 하단 35% 다") {
        val state = ShellSplitState()

        state.sidebarFraction shouldBe ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION
        state.bottomFraction shouldBe ShellSplitDefaults.DEFAULT_BOTTOM_FRACTION
        state.sidebarWidth(TOTAL_WIDTH, SPLITTER) shouldBeDp 220.dp
        state.bottomHeight(TOTAL_HEIGHT, SPLITTER) shouldBeDp 280.dp
    }

    test("세로 분할선을 오른쪽으로 끌면 사이드바가 그만큼 넓어진다") {
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = 40.dp, totalWidth = TOTAL_WIDTH, splitterThickness = SPLITTER)

        state.sidebarWidth(TOTAL_WIDTH, SPLITTER) shouldBeDp 260.dp
    }

    test("세로 분할선을 왼쪽으로 끌면 사이드바가 그만큼 좁아진다") {
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = (-20).dp, totalWidth = TOTAL_WIDTH, splitterThickness = SPLITTER)

        state.sidebarWidth(TOTAL_WIDTH, SPLITTER) shouldBeDp 200.dp
    }

    test("사이드바를 최소 폭 아래로 끌어도 180dp 를 유지한다") {
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = (-900).dp, totalWidth = TOTAL_WIDTH, splitterThickness = SPLITTER)

        state.sidebarWidth(TOTAL_WIDTH, SPLITTER) shouldBeDp ShellSplitDefaults.SIDEBAR_MIN_WIDTH
    }

    test("사이드바를 끝까지 넓혀도 분할선을 뺀 중앙 실제 폭이 320dp 로 남는다") {
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = 900.dp, totalWidth = TOTAL_WIDTH, splitterThickness = SPLITTER)

        val sidebar = state.sidebarWidth(TOTAL_WIDTH, SPLITTER)
        sidebar shouldBeDp (TOTAL_WIDTH - ShellSplitDefaults.CENTER_MIN_WIDTH - SPLITTER)
        // 중앙은 남은 폭을 받는다 — 분할선 두께까지 뺀 값이 최소 폭 이상이어야 한다.
        (TOTAL_WIDTH - sidebar - SPLITTER) shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_WIDTH
    }

    test("가로 분할선을 위로 끌면 하단이 넓어지고 아래로 끌면 좁아진다") {
        val state = ShellSplitState()

        state.resizeBottomBy(delta = (-30).dp, totalHeight = TOTAL_HEIGHT, splitterThickness = SPLITTER)
        state.bottomHeight(TOTAL_HEIGHT, SPLITTER) shouldBeDp 310.dp

        state.resizeBottomBy(delta = 30.dp, totalHeight = TOTAL_HEIGHT, splitterThickness = SPLITTER)
        state.bottomHeight(TOTAL_HEIGHT, SPLITTER) shouldBeDp 280.dp
    }

    test("하단을 최소 높이 아래로 끌어도 120dp 를 유지한다") {
        val state = ShellSplitState()

        state.resizeBottomBy(delta = 700.dp, totalHeight = TOTAL_HEIGHT, splitterThickness = SPLITTER)

        state.bottomHeight(TOTAL_HEIGHT, SPLITTER) shouldBeDp ShellSplitDefaults.BOTTOM_MIN_HEIGHT
    }

    test("하단을 끝까지 넓혀도 분할선을 뺀 중앙 실제 높이가 320dp 로 남는다") {
        val state = ShellSplitState()

        state.resizeBottomBy(delta = (-700).dp, totalHeight = TOTAL_HEIGHT, splitterThickness = SPLITTER)

        val bottom = state.bottomHeight(TOTAL_HEIGHT, SPLITTER)
        bottom shouldBeDp (TOTAL_HEIGHT - ShellSplitDefaults.CENTER_MIN_HEIGHT - SPLITTER)
        (TOTAL_HEIGHT - bottom - SPLITTER) shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_HEIGHT
    }

    test("두 최소 크기를 동시에 만족할 수 없을 만큼 창이 좁으면 최소 크기 비율대로 나눈다") {
        val narrow = 200.dp
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = (-500).dp, totalWidth = narrow, splitterThickness = SPLITTER)

        val expected = narrow * (
            ShellSplitDefaults.SIDEBAR_MIN_WIDTH /
                (ShellSplitDefaults.SIDEBAR_MIN_WIDTH + ShellSplitDefaults.CENTER_MIN_WIDTH)
            )
        state.sidebarWidth(narrow, SPLITTER) shouldBeDp expected
    }

    test("폭이 0 이면 비율을 바꾸지 않는다 — 나눗셈이 정의되지 않는다") {
        val state = ShellSplitState()

        state.resizeSidebarBy(delta = 40.dp, totalWidth = 0.dp, splitterThickness = SPLITTER)

        state.sidebarFraction shouldBe ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION
    }

    test("창이 줄어들면 저장된 비율이 아니라 최소 크기가 이긴다") {
        val state = ShellSplitState()

        // 22% 는 600dp 창에서 132dp 라 최소 폭 180dp 에 못 미친다.
        state.sidebarWidth(600.dp, SPLITTER) shouldBeDp ShellSplitDefaults.SIDEBAR_MIN_WIDTH
    }

    test("키보드 조절 단위는 8dp 이며 드래그와 같은 경로로 반영된다") {
        ShellSplitDefaults.KEYBOARD_STEP shouldBe 8.dp

        val keyboard = ShellSplitState()
        val drag = ShellSplitState()
        keyboard.resizeSidebarBy(ShellSplitDefaults.KEYBOARD_STEP, TOTAL_WIDTH, SPLITTER)
        drag.resizeSidebarBy(8.dp, TOTAL_WIDTH, SPLITTER)

        keyboard.sidebarFraction shouldBe drag.sidebarFraction
        keyboard.sidebarFraction shouldBeGreaterThan ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION
    }

    test("분할 비율은 세션 상태다 — 새 인스턴스는 직전 조절을 기억하지 않는다") {
        val session = ShellSplitState()
        session.resizeSidebarBy(120.dp, TOTAL_WIDTH, SPLITTER)
        session.sidebarFraction shouldBeGreaterThan ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION

        ShellSplitState().sidebarFraction shouldBe ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION
    }

    test("기본 비율은 0 과 1 사이의 정상 범위다") {
        ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION shouldBeGreaterThan 0f
        ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION shouldBeLessThan 1f
        ShellSplitDefaults.DEFAULT_BOTTOM_FRACTION shouldBeGreaterThan 0f
        ShellSplitDefaults.DEFAULT_BOTTOM_FRACTION shouldBeLessThan 1f
    }
})
