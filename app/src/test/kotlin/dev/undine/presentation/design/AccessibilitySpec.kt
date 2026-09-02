package dev.undine.presentation.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.domain.ThemeMode
import dev.undine.presentation.design.component.UndineIconButton
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineToast
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.design.component.UndineToolbarButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private const val TARGET = "a11y.target"
private const val OUTSIDE = "a11y.outside"
private const val INSIDE_FIRST = "a11y.inside.first"
private const val INSIDE_LAST = "a11y.inside.last"

/** 24dp 미만인 클릭 대상 — 보강 대상의 경계 아래 값이다. */
private val TINY_CONTENT = 4.dp

/** 이미 24dp 를 넘는 행 — 44·48dp 로 부풀리지 않는지 보는 값이다 (결정 G45-1). */
private val TALL_CONTENT = 48.dp

/** 라이트 토큰의 강조색 — 포커스 링이 이 색으로 그려진다. */
private val ACCENT = UndineTokenSet.Light.color.accent

/**
 * 접근성 공통 수정자의 계약 — 최소 클릭 영역·포커스 표시·대화상자 포커스 트랩과 ESC.
 *
 * 각 화면이 아니라 **공통 수정자**를 여기서 못박는다. 화면마다 같은 검증을 복제하면 새 화면이
 * 검증 없이 들어올 수 있고, 반대로 여기 하나가 깨지면 그 수정자를 쓰는 화면 전부가 함께 걸린다.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilitySpec : FunSpec({

    test("24dp 미만인 목록 행은 24dp 로 보강된다") {
        runComposeUiTest {
            setContent {
                Themed {
                    UndineListRow(onClick = {}, modifier = Modifier.testTag(TARGET)) {
                        Box(Modifier.size(TINY_CONTENT))
                    }
                }
            }

            onNodeWithTag(TARGET).assertHeightIsAtLeast(MinimumTargetSize)
        }
    }

    test("이미 24dp 를 넘는 목록 행은 더 키우지 않는다") {
        runComposeUiTest {
            setContent {
                Themed {
                    UndineListRow(
                        onClick = {},
                        modifier = Modifier.height(TALL_CONTENT).testTag(TARGET),
                    ) {
                        Box(Modifier.size(TINY_CONTENT))
                    }
                }
            }

            // 44·48dp 로 일괄 확대하면 이 값이 커진다 — 정보 밀도를 잃는 변경을 여기서 막는다.
            onNodeWithTag(TARGET).assertHeightIsEqualTo(TALL_CONTENT)
        }
    }

    test("아이콘이 작아도 아이콘 버튼의 클릭 대상은 24dp 이상이다") {
        runComposeUiTest {
            setContent {
                Themed {
                    UndineIconButton(
                        icon = tinyIcon(),
                        contentDescription = "작은 아이콘",
                        onClick = {},
                        modifier = Modifier.testTag(TARGET),
                    )
                }
            }

            onNodeWithTag(TARGET).assertWidthIsAtLeast(MinimumTargetSize)
            onNodeWithTag(TARGET).assertHeightIsAtLeast(MinimumTargetSize)
        }
    }

    test("포커스를 받은 버튼에는 강조색 포커스 표시가 그려진다") {
        runComposeUiTest {
            setContent {
                Themed {
                    UndineToolbarButton(label = "확인", onClick = {}, modifier = Modifier.testTag(TARGET))
                }
            }

            val beforeFocus = onNodeWithTag(TARGET).accentPixelCount()
            beforeFocus shouldBe 0

            onNodeWithTag(TARGET).requestFocus()
            waitForIdle()

            // 포커스가 어디 있는지 보이지 않으면 키보드만 쓰는 사용자는 길을 잃는다.
            onNodeWithTag(TARGET).accentPixelCount() shouldBeGreaterThan 0
        }
    }

    // 색조만 다른 알림은 색각 이상 사용자에게 전부 같은 알림이다 — 문구가 상황을 말해야 한다.
    test("상태 토스트는 색조와 무관하게 문구를 함께 보여 준다") {
        UndineToastTone.entries.forEach { tone ->
            runComposeUiTest {
                val message = "원격 작업이 ${tone.name} 상태입니다"
                setContent { Themed { UndineToast(message = message, tone = tone) } }

                onNodeWithText(message).assertIsDisplayed()
            }
        }
    }

    test("대화상자 표면은 ESC 로 닫히고 포커스가 밖으로 나가지 않는다") {
        runComposeUiTest {
            var dismissed = 0
            setContent {
                Themed {
                    Column {
                        UndineToolbarButton(label = "밖", onClick = {}, modifier = Modifier.testTag(OUTSIDE))
                        Column(modifier = Modifier.undineDialogSurface(onDismiss = { dismissed += 1 })) {
                            UndineToolbarButton(
                                label = "취소",
                                onClick = {},
                                modifier = Modifier.testTag(INSIDE_FIRST),
                            )
                            UndineToolbarButton(
                                label = "확인",
                                onClick = {},
                                modifier = Modifier.testTag(INSIDE_LAST),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 열리는 순간 포커스가 대화상자 안으로 들어온다 — 첫 Tab 이 뒤에 가린 화면으로 가지 않는다.
            onNodeWithTag(OUTSIDE).assertIsNotFocused()

            // 마지막 항목에서 Tab 을 눌러도 대화상자 밖으로 빠져나가지 않는다.
            onNodeWithTag(INSIDE_LAST).requestFocus()
            onNodeWithTag(INSIDE_LAST).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag(OUTSIDE).assertIsNotFocused()

            onNodeWithTag(INSIDE_FIRST).requestFocus()
            onNodeWithTag(INSIDE_FIRST).assertIsFocused()
            onNodeWithTag(INSIDE_FIRST).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            dismissed shouldBe 1
        }
    }
})

@Composable
private fun Themed(content: @Composable () -> Unit) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        Box(modifier = Modifier.background(UndineTokens.color.background)) { content() }
    }
}

private const val COLOR_TOLERANCE = 0.02f

/** 노드 안에서 강조색으로 칠해진 픽셀 수. 포커스 표시가 실제로 그려졌는지 렌더 결과로 확인한다. */
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.accentPixelCount(): Int {
    val pixels = captureToImage().toPixelMap()
    var count = 0
    (0 until pixels.width).forEach { x ->
        (0 until pixels.height).forEach { y ->
            if (pixels[x, y].isCloseTo(ACCENT)) count += 1
        }
    }
    return count
}

private fun Color.isCloseTo(other: Color): Boolean =
    abs(red - other.red) < COLOR_TOLERANCE &&
        abs(green - other.green) < COLOR_TOLERANCE &&
        abs(blue - other.blue) < COLOR_TOLERANCE

/** 24dp 보강이 실제로 필요한 아이콘 — 기본 크기가 최소 클릭 영역보다 작다. */
private fun tinyIcon(): ImageVector =
    ImageVector.Builder(
        defaultWidth = TINY_CONTENT,
        defaultHeight = TINY_CONTENT,
        viewportWidth = 1f,
        viewportHeight = 1f,
    ).apply {
        addPath(PathBuilder().moveTo(0f, 0f).lineTo(1f, 1f).close().nodes)
    }.build()
