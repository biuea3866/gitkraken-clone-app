package dev.undine.presentation.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.search.SearchProgress
import dev.undine.domain.RefName
import dev.undine.domain.ThemeMode
import dev.undine.domain.search.SEARCH_ZONE
import dev.undine.domain.search.commitOf
import dev.undine.domain.search.hashOf
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.searchTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf

/** 결정 G45-2: WCAG 1.4.4 가 요구하는 확대 상한. */
private const val LARGE_FONT_SCALE = 2.0f

private val PANEL_WIDTH = 420.dp

/** 여섯 축과 결과 영역이 2.0 배율에서 한 화면에 담기지 않는 높이 — 넘침을 강제한다. */
private val CONSTRAINED_HEIGHT = 280.dp

/** 같은 배율에서 넘치지 않는 높이. 좁은 패널의 크기를 이 높이의 크기와 대조한다. */
private val ROOMY_HEIGHT = 720.dp

private val REFS = listOf(RefName("refs/heads/main"))

private val FIRST = commitOf(id = hashOf("aa1"), message = "fix login timeout")
private val SECOND = commitOf(id = hashOf("bb2"), message = "fix login redirect")

/** `search.*` 는 내장 목록에 없어 자기 맵으로 카탈로그를 만든다 (wave 3 결정 A3). */
private val CATALOG = StringCatalog(translations = searchTranslations, defaultLocale = DEFAULT_LOCALE)

private val STRINGS = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)

/**
 * 검색 패널의 글꼴 확대 회귀 — AC 10 의 "주요 화면 일부는 2.0 렌더 회귀 테스트로 겹침·잘림을 고정한다".
 *
 * 이 패널이 대상인 이유는 **한 화면에 조작 노드가 가장 많이 쌓이기 때문**이다 — 입력 축 여섯에
 * 지우기 버튼과 결과 목록이 이어진다. 확대하면 배선이 준 높이를 먼저 넘어서고, 넘친 입력칸은
 * 높이 0 으로 측정돼 조용히 사라진다 (키보드로도 마우스로도 닿지 않는다).
 *
 * 노드가 존재한다는 사실만으로는 겹침·잘림이 잡히지 않으므로 세 축을 따로 고정한다 —
 * (1) 좁은 패널에서도 여섯 축과 결과 영역에 **닿을 수 있는가**,
 * (2) 축의 사각형이 서로 **겹치지 않는가**,
 * (3) 좁은 패널의 축이 넉넉한 패널과 **같은 크기로** 그려지는가(눌려 잘리지 않았는가).
 */
@OptIn(ExperimentalTestApi::class)
class SearchPanelAccessibilitySpec : FunSpec({

    test("fontScale 2.0 의 좁은 패널에서도 여섯 필터와 지우기·결과 영역에 모두 닿는다") {
        runComposeUiTest {
            setContent { PanelUnderTest(stateWithResults(), CONSTRAINED_HEIGHT) }

            SearchField.entries.forEach { field ->
                onNodeWithTag(SearchTags.field(field)).performScrollTo().assertIsDisplayed()
            }
            onNodeWithTag(SearchTags.CLEAR).performScrollTo().assertIsDisplayed()
            onNodeWithTag(SearchTags.RESULT_LIST).assertIsDisplayed()
            onNodeWithTag(SearchTags.row(FIRST.id)).assertIsDisplayed()
        }
    }

    test("fontScale 2.0 에서 여섯 필터의 사각형은 서로 겹치지 않는다") {
        runComposeUiTest {
            setContent { PanelUnderTest(stateWithResults(), CONSTRAINED_HEIGHT) }

            // 스크롤 위치와 무관하게 배치 좌표로 견준다 — 화면 밖 축도 같은 좌표계에 놓인다.
            val boxes = SearchField.entries.associateWith { field ->
                onNodeWithTag(SearchTags.field(field)).fetchSemanticsNode().boundsInRoot
            }
            overlappingPairs(boxes).shouldBeEmpty()
        }
    }

    test("fontScale 2.0 의 좁은 패널은 넉넉한 패널과 같은 크기로 필터를 그린다") {
        // 높이가 모자랄 때 축이 눌리거나 잘리면 두 크기가 달라진다. 같으면 넘친 만큼 스크롤로
        // 밀려났을 뿐 아무것도 줄어들지 않았다는 뜻이다.
        val roomy = filterSizesAt(ROOMY_HEIGHT)
        val constrained = filterSizesAt(CONSTRAINED_HEIGHT)

        constrained shouldBe roomy
        constrained.filterValues { size -> size.width <= 0 || size.height <= 0 }.keys.shouldBeEmpty()
    }

    test("fontScale 2.0 에서 스크롤해 닿은 마지막 필터에 입력할 수 있다") {
        runComposeUiTest {
            val state = stateWithResults()
            setContent { PanelUnderTest(state, CONSTRAINED_HEIGHT) }

            val until = onNodeWithTag(SearchTags.field(SearchField.UNTIL))
            until.performScrollTo()
            until.requestFocus()
            until.performTextInput("2026-03-10")
            waitForIdle()

            state.queryOf(SearchField.UNTIL) shouldBe "2026-03-10"
        }
    }
})

/** 확대 배율에서 축 여섯의 실제 레이아웃 크기. 패널 높이만 바꿔 두 번 재고 대조한다. */
@OptIn(ExperimentalTestApi::class)
private fun filterSizesAt(height: Dp): Map<SearchField, IntSize> {
    val sizes = mutableMapOf<SearchField, IntSize>()
    runComposeUiTest {
        setContent { PanelUnderTest(stateWithResults(), height) }
        SearchField.entries.forEach { field ->
            sizes[field] = onNodeWithTag(SearchTags.field(field)).fetchSemanticsNode().size
        }
    }
    return sizes
}

/** 서로 만나는 축 쌍. 두 사각형이 겹치면 그만큼 글자가 서로를 덮는다. */
private fun overlappingPairs(boxes: Map<SearchField, Rect>): List<String> {
    val entries = boxes.entries.toList()
    return entries.flatMapIndexed { index, (field, box) ->
        entries.drop(index + 1)
            .filter { (_, other) -> box.meets(other) }
            .map { (otherField, _) -> "$field ↔ $otherField" }
    }
}

private fun Rect.meets(other: Rect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** 결과 목록까지 그려진 상태 — 결과 영역이 확대에서 사라지지 않는지 함께 보기 위해서다. */
private fun stateWithResults(): SearchState {
    val searchCommits = mockk<SearchCommitsUseCase>()
    every { searchCommits.execute(any(), any()) } returns
        flowOf(SearchProgress.Match(FIRST), SearchProgress.Match(SECOND))
    return SearchState(
        searchCommits = searchCommits,
        scope = CoroutineScope(Dispatchers.Unconfined),
        refs = REFS,
        zone = SEARCH_ZONE,
    ).apply { updateQuery(SearchField.MESSAGE, "fix") }
}

@Composable
private fun PanelUnderTest(state: SearchState, height: Dp) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, LARGE_FONT_SCALE),
        LocalStrings provides STRINGS,
    ) {
        UndineTheme(themeMode = ThemeMode.LIGHT) {
            Box(Modifier.width(PANEL_WIDTH).height(height)) {
                SearchPanel(state = state)
            }
        }
    }
}
