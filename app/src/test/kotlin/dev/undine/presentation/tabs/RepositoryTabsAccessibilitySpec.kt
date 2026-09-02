package dev.undine.presentation.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabId
import dev.undine.application.session.TabSession
import dev.undine.domain.RepositoryPath
import dev.undine.domain.ThemeMode
import dev.undine.presentation.design.MinimumTargetSize
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.tabs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")
private const val ALPHA_NAME = "alpha"
private const val BETA_NAME = "beta"

private val STRINGS = builtInStringCatalog().stringsFor(DEFAULT_LOCALE, devBuild = false)
private val CLOSE_LABEL = STRINGS.tabs.closeTab

private fun tabIdAt(index: Int) = TabId(index.toLong() + 1)

/**
 * 탭 막대의 접근성 — 닫기가 독립 노드인지, Tab 순서가 시각적 배치와 같은지, 포커스가 보이는지.
 *
 * 닫기가 탭 자체의 시맨틱스에 병합돼 있으면 **스크린리더·키보드·클릭 전부 닫기에 닿지 못한다** —
 * 그런데도 "탭 막대에 닫기 글자가 보인다" 는 검증은 통과한다 (UND-83 이 그 지점에서 멈췄다).
 * 그래서 여기서는 보이는지가 아니라 **개별로 닿아 눌러지는지**를 본다.
 */
@OptIn(ExperimentalTestApi::class)
class RepositoryTabsAccessibilitySpec : FunSpec({

    test("탭 닫기는 탭 선택과 별개의 노드로 노출되고 눌러서 그 탭의 닫기를 요청한다") {
        runComposeUiTest {
            val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
            val activated = mutableListOf<TabId>()
            val closeRequests = mutableListOf<TabCloseRequest>()
            setContent { TabsUnderTest(state, activated::add, closeRequests::add) }

            // 탭마다 닫기 노드가 하나씩 — 탭 노드에 병합됐다면 눌러도 아무 일이 없다.
            val closeNodes = onAllNodesWithText(CLOSE_LABEL)
            closeNodes.fetchSemanticsNodes() shouldHaveSize 2

            closeNodes[1].performClick()
            waitForIdle()

            // 닫기를 눌렀는데 탭이 활성화되면 두 동작이 한 노드에 겹쳐 있다는 뜻이다.
            activated.shouldContainExactly(emptyList())
            closeRequests shouldContainExactly listOf(TabCloseRequest.Ready(tabIdAt(1)))
        }
    }

    test("탭을 누르면 닫기 요청 없이 그 탭만 활성화된다") {
        runComposeUiTest {
            val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
            val activated = mutableListOf<TabId>()
            val closeRequests = mutableListOf<TabCloseRequest>()
            setContent { TabsUnderTest(state, activated::add, closeRequests::add) }

            onNodeWithText(BETA_NAME).performClick()
            waitForIdle()

            activated shouldContainExactly listOf(tabIdAt(1))
            closeRequests.shouldContainExactly(emptyList())
        }
    }

    test("닫힌 뒤 탭이 하나만 남으면 탭 막대가 사라진다") {
        runComposeUiTest {
            val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
            setContent { TabsUnderTest(state) }

            onAllNodesWithText(CLOSE_LABEL).fetchSemanticsNodes() shouldHaveSize 2

            // 배선이 UseCase 결과로 넘겨주는 그 스냅샷을 반영한다.
            state.apply(snapshotOf(listOf(ALPHA), activeIndex = 0))
            waitForIdle()

            onAllNodesWithText(CLOSE_LABEL).fetchSemanticsNodes() shouldHaveSize 0
        }
    }

    test("Tab 이동 순서가 왼쪽에서 오른쪽 배치 순서와 일치한다") {
        runComposeUiTest {
            val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
            setContent { TabsUnderTest(state) }

            // 시각적 배치: alpha 탭 · alpha 닫기 · beta 탭 · beta 닫기
            val alphaLeft = onNodeWithText(ALPHA_NAME).getBoundsInRoot().left
            val betaLeft = onNodeWithText(BETA_NAME).getBoundsInRoot().left
            (alphaLeft < betaLeft) shouldBe true

            onNodeWithText(ALPHA_NAME).requestFocus()
            onNodeWithText(ALPHA_NAME).assertIsFocused()

            onNodeWithText(ALPHA_NAME).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onAllNodesWithText(CLOSE_LABEL)[0].assertIsFocused()

            onAllNodesWithText(CLOSE_LABEL)[0].performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithText(BETA_NAME).assertIsFocused()

            onNodeWithText(BETA_NAME).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onAllNodesWithText(CLOSE_LABEL)[1].assertIsFocused()
        }
    }

    test("탭과 닫기 모두 최소 클릭 영역을 지킨다") {
        runComposeUiTest {
            val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
            setContent { TabsUnderTest(state) }

            onNodeWithText(ALPHA_NAME).assertHeightIsAtLeast(MinimumTargetSize)
            onAllNodesWithText(CLOSE_LABEL)[0].assertWidthIsAtLeast(MinimumTargetSize)
            onAllNodesWithText(CLOSE_LABEL)[0].assertHeightIsAtLeast(MinimumTargetSize)
        }
    }

    test("경로를 잃은 탭도 선택과 닫기에 개별로 닿을 수 있다") {
        runComposeUiTest {
            val state = RepositoryTabsState(
                snapshotOf(listOf(ALPHA, BETA), activeIndex = 0, missingPathAt = 0),
            )
            val closeRequests = mutableListOf<TabCloseRequest>()
            setContent { TabsUnderTest(state, onCloseRequested = closeRequests::add) }

            // 경로 상실 안내는 그 탭의 선택 노드에 병합돼 있어야 한다 — 떨어져 나가면 어느 탭의
            // 안내인지 알 수 없다.
            onNodeWithText(STRINGS.tabs.missingPath).assert(hasClickAction())
            onAllNodesWithText(CLOSE_LABEL)[0].performClick()
            waitForIdle()

            closeRequests shouldContainExactly listOf(TabCloseRequest.Ready(tabIdAt(0)))
        }
    }
})

@Composable
private fun TabsUnderTest(
    state: RepositoryTabsState,
    onActivate: (TabId) -> Unit = {},
    onCloseRequested: (TabCloseRequest) -> Unit = {},
) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalStrings provides STRINGS) {
            RepositoryTabs(state = state, onActivate = onActivate, onCloseRequested = onCloseRequested)
        }
    }
}

private fun snapshotOf(
    paths: List<RepositoryPath>,
    activeIndex: Int,
    missingPathAt: Int? = null,
): RepositorySessionSnapshot = RepositorySessionSnapshot(
    tabs = paths.mapIndexed { index, path ->
        TabSession(
            id = tabIdAt(index),
            path = path,
            availability = if (index == missingPathAt) TabAvailability.MissingPath else TabAvailability.Available,
            resourcesLoaded = index == activeIndex,
        )
    },
    activeTabId = tabIdAt(activeIndex),
)
