package dev.undine.presentation.shell

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.undine.domain.WindowBounds
import dev.undine.presentation.design.SpacingTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `Settings.window` 복원 — 창을 실제로 띄우지 않고 [shellWindowStateFor] 가 만든 값으로 검증한다.
 * 창 위치는 1차 계약에 없어 **복원 대상이 아니라는 사실**까지 테스트로 고정한다.
 */
class ShellWindowStateSpec : FunSpec({

    test("저장된 창 크기를 복원한다") {
        val state = shellWindowStateFor(WindowBounds(width = 1440, height = 900, maximized = false))

        state.size.width shouldBe 1440.dp
        state.size.height shouldBe 900.dp
    }

    test("최대화 상태를 복원한다") {
        val maximized = shellWindowStateFor(WindowBounds(1440, 900, maximized = true))
        val floating = shellWindowStateFor(WindowBounds(1440, 900, maximized = false))

        maximized.placement shouldBe WindowPlacement.Maximized
        floating.placement shouldBe WindowPlacement.Floating
    }

    test("최대화 상태여도 저장된 크기는 그대로 들고 있다 — 창 복귀 시 쓰인다") {
        val state = shellWindowStateFor(WindowBounds(1280, 720, maximized = true))

        state.size.width shouldBe 1280.dp
        state.size.height shouldBe 720.dp
    }

    test("창 위치는 복원하지 않고 플랫폼 기본 위치로 둔다") {
        val state = shellWindowStateFor(WindowBounds(1440, 900, maximized = false))

        state.position.shouldBeInstanceOf<WindowPosition.PlatformDefault>()
    }

    test("저장된 크기가 0 이하면 기본 크기로 떨어진다 — 보이지 않는 창을 만들지 않는다") {
        val zero = shellWindowStateFor(WindowBounds(width = 0, height = 0, maximized = false))
        val negative = shellWindowStateFor(WindowBounds(width = -1, height = -1, maximized = false))

        zero.size shouldBe DEFAULT_WINDOW_SIZE
        negative.size shouldBe DEFAULT_WINDOW_SIZE
    }

    test("저장된 크기가 최소 창 크기보다 작으면 최소 크기로 올린다") {
        val state = shellWindowStateFor(WindowBounds(width = 100, height = 80, maximized = false))

        state.size.width shouldBe MIN_WINDOW_WIDTH
        state.size.height shouldBe MIN_WINDOW_HEIGHT
    }

    test("최소 창 크기는 두 영역 최소값에 분할선 두께까지 더한 값이다") {
        // 두께를 빼먹으면 최소 크기로 복원한 창에서 중앙이 그만큼 작아진다 (AppShellSpec 회귀 테스트).
        MIN_WINDOW_WIDTH shouldBe (
            ShellSplitDefaults.SIDEBAR_MIN_WIDTH +
                ShellSplitDefaults.SPLITTER_THICKNESS +
                ShellSplitDefaults.CENTER_MIN_WIDTH
            )
        MIN_WINDOW_HEIGHT shouldBe (
            ShellSplitDefaults.CENTER_MIN_HEIGHT +
                ShellSplitDefaults.SPLITTER_THICKNESS +
                ShellSplitDefaults.BOTTOM_MIN_HEIGHT
            )
        MIN_WINDOW_WIDTH shouldBe 504.dp
        MIN_WINDOW_HEIGHT shouldBe 444.dp
    }

    test("최소 창 크기에서 각 영역 최소값을 빼면 분할선 두께만 남는다 — 중앙이 잠식되지 않는다") {
        val leftoverWidth = MIN_WINDOW_WIDTH -
            ShellSplitDefaults.SIDEBAR_MIN_WIDTH -
            ShellSplitDefaults.CENTER_MIN_WIDTH
        val leftoverHeight = MIN_WINDOW_HEIGHT -
            ShellSplitDefaults.CENTER_MIN_HEIGHT -
            ShellSplitDefaults.BOTTOM_MIN_HEIGHT

        leftoverWidth shouldBe ShellSplitDefaults.SPLITTER_THICKNESS
        leftoverHeight shouldBe ShellSplitDefaults.SPLITTER_THICKNESS
    }

    test("분할선 두께는 그리는 쪽과 같은 간격 토큰에서 온다") {
        ShellSplitDefaults.SPLITTER_THICKNESS shouldBe SpacingTokens.Default.extraSmall
    }
})
