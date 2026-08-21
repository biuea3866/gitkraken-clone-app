package dev.undine.presentation.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds
import dev.undine.presentation.design.ColorTokens
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.shellTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.shouldBeGreaterThan as floatShouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val TOOLBAR_CONTENT = "test.toolbar"
private const val SIDEBAR_CONTENT = "test.sidebar"
private const val CENTER_CONTENT = "test.center"
private const val BOTTOM_CONTENT = "test.bottom"

/** 테스트 화면(1024x768) 안에 들어가는 크기 — 넘으면 셸이 잘려 분할 기준 길이가 달라진다. */
private val SHELL_WIDTH = 1000.dp
private val SHELL_HEIGHT = 700.dp
private val TOOLBAR_HEIGHT = 40.dp

/** 툴바를 뺀 나머지 — 세로 분할의 기준 길이다. */
private val SPLIT_HEIGHT = SHELL_HEIGHT - TOOLBAR_HEIGHT

/** 셸이 그리는 분할선 두께 — 최소 크기 판정이 이 값을 빼고 계산한다. */
private val SPLITTER = ShellSplitDefaults.SPLITTER_THICKNESS

private const val DP_TOLERANCE = 0.5

private infix fun Dp.shouldBeDp(expected: Dp) =
    value.toDouble() shouldBe (expected.value.toDouble() plusOrMinus DP_TOLERANCE)

/** 측정된 크기의 하한 검증. 픽셀 반올림 오차는 최소 크기 위반이 아니다. */
private infix fun Dp.shouldBeAtLeastDp(minimum: Dp) =
    value.toDouble() shouldBeGreaterThanOrEqualTo (minimum.value.toDouble() - DP_TOLERANCE)

/** 셸 화면 — 슬롯 배치·분할선 드래그·키보드 조작·최소 크기·테마 전환. */
@OptIn(ExperimentalTestApi::class)
class AppShellSpec : FunSpec({

    test("네 슬롯이 툴바·사이드바·중앙·하단 자리에 각각 렌더링된다") {
        runComposeUiTest {
            setContent { ShellUnderTest(ShellSplitState()) }

            onNodeWithTag(TOOLBAR_CONTENT).assertIsDisplayed()
            onNodeWithTag(SIDEBAR_CONTENT).assertIsDisplayed()
            onNodeWithTag(CENTER_CONTENT).assertIsDisplayed()
            onNodeWithTag(BOTTOM_CONTENT).assertIsDisplayed()

            val toolbar = onNodeWithTag(TOOLBAR_CONTENT).getBoundsInRoot()
            val sidebar = onNodeWithTag(SIDEBAR_CONTENT).getBoundsInRoot()
            val center = onNodeWithTag(CENTER_CONTENT).getBoundsInRoot()
            val bottom = onNodeWithTag(BOTTOM_CONTENT).getBoundsInRoot()

            toolbar.top shouldBeLessThan sidebar.top
            toolbar.bottom shouldBeLessThanOrEqualTo sidebar.top
            toolbar.bottom shouldBeLessThanOrEqualTo center.top
            sidebar.right shouldBeLessThan center.left
            center.bottom shouldBeLessThan bottom.top
            bottom.left shouldBeDp center.left
        }
    }

    test("슬롯에 아무것도 넣지 않아도 예외 없이 렌더링된다") {
        runComposeUiTest {
            setContent { EmptyShellUnderTest(ShellSplitState(), ThemeMode.LIGHT) }

            onNodeWithTag(ShellTags.ROOT).assertIsDisplayed()
            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).assertIsDisplayed()
            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).assertIsDisplayed()
            onNodeWithTag(ShellTags.SIDEBAR).assertWidthIsEqualTo(
                SHELL_WIDTH * ShellSplitDefaults.DEFAULT_SIDEBAR_FRACTION,
            )
        }
    }

    test("세로 분할선을 드래그하면 사이드바가 넓어지고 리컴포지션 뒤에도 유지된다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            val shellState = AppShellState()
            setContent { ShellUnderTest(splitState, shellState = shellState) }

            val before = splitState.sidebarFraction
            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(80f, 0f))
                up()
            }
            waitForIdle()

            splitState.sidebarFraction floatShouldBeGreaterThan before
            val widened = splitState.sidebarWidth(SHELL_WIDTH, SPLITTER)

            // 선택 상태를 바꿔 리컴포지션을 유발해도 비율은 세션 상태로 남는다.
            shellState.selectFile("src/main/kotlin/Any.kt")
            waitForIdle()

            splitState.sidebarWidth(SHELL_WIDTH, SPLITTER) shouldBeDp widened
            onNodeWithTag(ShellTags.SIDEBAR).assertWidthIsEqualTo(widened)
        }
    }

    test("사이드바를 최소 폭 미만으로 드래그해도 180dp 가 유지된다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellUnderTest(splitState) }

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(-5000f, 0f))
                up()
            }
            waitForIdle()

            splitState.sidebarWidth(SHELL_WIDTH, SPLITTER) shouldBeDp ShellSplitDefaults.SIDEBAR_MIN_WIDTH
            onNodeWithTag(ShellTags.SIDEBAR).assertWidthIsEqualTo(ShellSplitDefaults.SIDEBAR_MIN_WIDTH)
        }
    }

    test("포커스된 세로 분할선은 좌우 방향키로 8dp 씩 움직인다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellUnderTest(splitState) }
            val initial = splitState.sidebarWidth(SHELL_WIDTH, SPLITTER)

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).requestFocus()
            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performKeyInput { pressKey(Key.DirectionRight) }
            waitForIdle()

            splitState.sidebarWidth(SHELL_WIDTH, SPLITTER) shouldBeDp (initial + ShellSplitDefaults.KEYBOARD_STEP)

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performKeyInput { pressKey(Key.DirectionLeft) }
            waitForIdle()

            splitState.sidebarWidth(SHELL_WIDTH, SPLITTER) shouldBeDp initial
        }
    }

    test("키보드로 계속 좁혀도 최소 폭 180dp 아래로 내려가지 않는다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellUnderTest(splitState) }

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).requestFocus()
            repeat(20) {
                onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performKeyInput { pressKey(Key.DirectionLeft) }
            }
            waitForIdle()

            splitState.sidebarWidth(SHELL_WIDTH, SPLITTER) shouldBeDp ShellSplitDefaults.SIDEBAR_MIN_WIDTH
            onNodeWithTag(ShellTags.SIDEBAR).assertWidthIsEqualTo(ShellSplitDefaults.SIDEBAR_MIN_WIDTH)
        }
    }

    test("포커스된 가로 분할선은 위아래 방향키로 하단 높이를 8dp 씩 바꾸고 최소 높이를 지킨다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellUnderTest(splitState) }
            val initial = splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER)

            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).requestFocus()
            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()

            splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER) shouldBeDp (initial + ShellSplitDefaults.KEYBOARD_STEP)

            repeat(60) {
                onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performKeyInput { pressKey(Key.DirectionDown) }
            }
            waitForIdle()

            splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER) shouldBeDp ShellSplitDefaults.BOTTOM_MIN_HEIGHT
        }
    }

    test("가로 분할선을 드래그하면 하단 높이가 바뀌고 리컴포지션 뒤에도 유지된다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            val shellState = AppShellState()
            setContent { ShellUnderTest(splitState, shellState = shellState) }

            val before = splitState.bottomFraction
            // 위로 끌면 하단이 넓어진다 — resizeBottomBy 는 아래를 양수로 본다.
            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(0f, -60f))
                up()
            }
            waitForIdle()

            splitState.bottomFraction floatShouldBeGreaterThan before
            val heightened = splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER)
            onNodeWithTag(ShellTags.BOTTOM).assertHeightIsEqualTo(heightened)

            shellState.selectFile("src/main/kotlin/Any.kt")
            waitForIdle()

            splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER) shouldBeDp heightened
            onNodeWithTag(ShellTags.BOTTOM).assertHeightIsEqualTo(heightened)
        }
    }

    test("하단을 최소 높이 미만으로 드래그해도 120dp 가 유지된다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellUnderTest(splitState) }

            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(0f, 5000f))
                up()
            }
            waitForIdle()

            splitState.bottomHeight(SPLIT_HEIGHT, SPLITTER) shouldBeDp ShellSplitDefaults.BOTTOM_MIN_HEIGHT
            onNodeWithTag(ShellTags.BOTTOM).assertHeightIsEqualTo(ShellSplitDefaults.BOTTOM_MIN_HEIGHT)
        }
    }

    test("사이드바를 뺀 나머지 폭이 중앙 최소 폭보다 넓게 남는다") {
        runComposeUiTest {
            setContent { ShellUnderTest(ShellSplitState()) }

            onNodeWithTag(CENTER_CONTENT).getBoundsInRoot().width shouldBeGreaterThan
                ShellSplitDefaults.CENTER_MIN_WIDTH
        }
    }

    test("분할선을 양쪽 끝까지 끌어도 중앙 실제 크기가 최소값 아래로 내려가지 않는다") {
        runComposeUiTest {
            setContent { ShellUnderTest(ShellSplitState()) }

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(5000f, 0f))
                up()
            }
            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(0f, -5000f))
                up()
            }
            waitForIdle()

            // 분할선 두께를 최소 크기 계산에서 빠뜨리면 여기서 두께만큼 모자란다.
            val center = onNodeWithTag(CENTER_CONTENT).getBoundsInRoot()
            center.width shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_WIDTH
            center.height shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_HEIGHT
        }
    }

    test("최소 창 크기로 복원한 셸에서도 중앙 실제 크기가 최소값 아래로 내려가지 않는다") {
        runComposeUiTest {
            // 저장값이 최소보다 작아 shellWindowStateFor 가 최소 크기로 올린 창을 그대로 그린다.
            val restored = shellWindowStateFor(WindowBounds(width = 100, height = 80, maximized = false))
            restored.size shouldBe DpSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
            setContent { ShellAtSize(ShellSplitState(), restored.size) }

            val center = onNodeWithTag(ShellTags.CENTER).getBoundsInRoot()
            center.width shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_WIDTH
            center.height shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_HEIGHT
            onNodeWithTag(ShellTags.SIDEBAR).getBoundsInRoot().width shouldBeAtLeastDp
                ShellSplitDefaults.SIDEBAR_MIN_WIDTH
            onNodeWithTag(ShellTags.BOTTOM).getBoundsInRoot().height shouldBeAtLeastDp
                ShellSplitDefaults.BOTTOM_MIN_HEIGHT
        }
    }

    test("최소 크기 창에서 분할선을 끝까지 끌어도 중앙 실제 크기가 최소값 이상으로 남는다") {
        runComposeUiTest {
            val splitState = ShellSplitState()
            setContent { ShellAtSize(splitState, DpSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)) }

            onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(5000f, 0f))
                up()
            }
            onNodeWithTag(ShellTags.BOTTOM_SPLITTER).performTouchInput {
                down(center)
                moveBy(Offset(0f, -5000f))
                up()
            }
            waitForIdle()

            val center = onNodeWithTag(ShellTags.CENTER).getBoundsInRoot()
            center.width shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_WIDTH
            center.height shouldBeAtLeastDp ShellSplitDefaults.CENTER_MIN_HEIGHT
        }
    }

    test("테마를 전환하면 셸 배경과 분할선 색이 각 토큰 값으로 함께 바뀐다") {
        val light = captureShellColors(ThemeMode.LIGHT)
        val dark = captureShellColors(ThemeMode.DARK)

        light.background shouldBe ColorTokens.Light.background
        light.splitter shouldBe ColorTokens.Light.border
        dark.background shouldBe ColorTokens.Dark.background
        dark.splitter shouldBe ColorTokens.Dark.border
        dark.background shouldNotBe light.background
        dark.splitter shouldNotBe light.splitter
    }
})

private class ShellColors(val background: Color, val splitter: Color)

/** 슬롯이 빈 셸을 그려 배경·분할선 픽셀을 읽는다 — 색이 토큰에서 오는지 확인하는 직접적인 방법이다. */
@OptIn(ExperimentalTestApi::class)
private fun captureShellColors(themeMode: ThemeMode): ShellColors {
    lateinit var colors: ShellColors
    runComposeUiTest {
        setContent { EmptyShellUnderTest(ShellSplitState(), themeMode) }

        val root = onNodeWithTag(ShellTags.ROOT).captureToImage().toPixelMap()
        val splitter = onNodeWithTag(ShellTags.SIDEBAR_SPLITTER).captureToImage().toPixelMap()
        colors = ShellColors(
            background = root[1, root.height - 2],
            splitter = splitter[splitter.width / 2, splitter.height / 2],
        )
    }
    return colors
}

@Composable
private fun ShellUnderTest(
    splitState: ShellSplitState,
    themeMode: ThemeMode = ThemeMode.LIGHT,
    shellState: AppShellState = AppShellState(),
) {
    ShellHost(themeMode) {
        AppShell(
            state = shellState,
            modifier = Modifier.size(SHELL_WIDTH, SHELL_HEIGHT),
            splitState = splitState,
            slots = AppShellSlots(
                toolbar = { Box(Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).testTag(TOOLBAR_CONTENT)) },
                sidebar = { Box(Modifier.fillMaxSize().testTag(SIDEBAR_CONTENT)) },
                center = { Box(Modifier.fillMaxSize().testTag(CENTER_CONTENT)) },
                bottom = { Box(Modifier.fillMaxSize().testTag(BOTTOM_CONTENT)) },
            ),
        )
    }
}

/** 슬롯이 빈 셸을 임의 크기로 그린다 — 창 복원 크기를 그대로 넣어 실제 배치를 재는 데 쓴다. */
@Composable
private fun ShellAtSize(splitState: ShellSplitState, size: DpSize) {
    ShellHost(ThemeMode.LIGHT) {
        AppShell(
            state = AppShellState(),
            modifier = Modifier.size(size),
            splitState = splitState,
        )
    }
}

@Composable
private fun EmptyShellUnderTest(splitState: ShellSplitState, themeMode: ThemeMode) {
    ShellHost(themeMode) {
        AppShell(
            state = AppShellState(),
            modifier = Modifier.size(SHELL_WIDTH, SHELL_HEIGHT),
            splitState = splitState,
        )
    }
}

/** 테마와 문자열 카탈로그를 갖춘 최소 호스트. shell 네임스페이스는 아직 내장 목록에 없어 직접 만든다. */
@Composable
private fun ShellHost(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val catalog = StringCatalog(translations = shellTranslations, defaultLocale = DEFAULT_LOCALE)
    UndineTheme(themeMode = themeMode) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}
