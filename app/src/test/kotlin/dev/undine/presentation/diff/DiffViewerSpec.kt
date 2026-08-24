package dev.undine.presentation.diff

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffResult
import dev.undine.domain.ThemeMode
import dev.undine.presentation.design.ColorTokens
import dev.undine.presentation.design.DiffChangeMark
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.diff
import dev.undine.presentation.i18n.diffTranslations
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan as intShouldBeLessThan
import io.kotest.matchers.shouldBe

private val VIEWER_WIDTH = 900.dp
private val VIEWER_HEIGHT = 400.dp

private val catalog = StringCatalog(translations = diffTranslations, defaultLocale = DEFAULT_LOCALE)
private val diffStrings = catalog.stringsFor(DEFAULT_LOCALE, devBuild = false).diff

/** diff 뷰어 화면 — 두 번호 열·기호·배경, 모드 전환, 사유 안내, hunk 콜백, 가상 스크롤. */
@OptIn(ExperimentalTestApi::class)
class DiffViewerSpec : FunSpec({

    test("한 줄 수정이 삭제·추가 두 행으로 나오고 기호가 함께 표시된다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(computed(singleLineEditHunk())) }

            onNodeWithTag(DiffTags.ROOT).assertIsDisplayed()
            onNodeWithText("    val value = 1").assertIsDisplayed()
            onNodeWithText("    val value = 2").assertIsDisplayed()
            onNodeWithText(DiffChangeMark.DELETION.symbol).assertIsDisplayed()
            onNodeWithText(DiffChangeMark.ADDITION.symbol).assertIsDisplayed()
        }
    }

    test("원본·변경본 라인 번호가 두 열로 함께 표시된다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(computed(singleLineEditHunk())) }

            val oldColumns = onAllNodesWithTag(DiffTags.OLD_LINE_NUMBER, useUnmergedTree = true)
            val newColumns = onAllNodesWithTag(DiffTags.NEW_LINE_NUMBER, useUnmergedTree = true)

            oldColumns.fetchSemanticsNodes().size shouldBe 4
            newColumns.fetchSemanticsNodes().size shouldBe 4
            oldColumns[0].getBoundsInRoot().left shouldBeLessThan newColumns[0].getBoundsInRoot().left
        }
    }

    test("추가·삭제 줄에는 각 토큰 배경색이 깔린다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(computed(singleLineEditHunk())) }

            val deleted = pixelOf(onNodeWithTag(DiffTags.row(2)).captureToImage().toPixelMap())
            val added = pixelOf(onNodeWithTag(DiffTags.row(3)).captureToImage().toPixelMap())

            deleted shouldBe ColorTokens.Light.deletionSurface
            added shouldBe ColorTokens.Light.additionSurface
        }
    }

    test("추가·삭제 줄은 changedRanges 의 토큰만 굵게 강조하고 문맥 줄은 강조하지 않는다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(computed(singleLineEditHunk())) }

            // 기대값을 화면이 받은 `changedRanges` 에서 그대로 끌어온다 — 강조가 본문 전체로 번지거나
            // span 이 사라지면 어긋난다. 문맥 줄은 구간이 비어 강조가 하나도 없어야 한다.
            val cells = diffRowsOf(listOf(singleLineEditHunk()), DiffViewMode.UNIFIED, DEFAULT_TAB_WIDTH)
                .filterIsInstance<DiffRow.Unified>()
                .map { it.cell }

            cells.forEach { cell ->
                withClue("`${cell.text}` 의 강조 구간") {
                    boldRangesOf(renderedText(cell.text)) shouldContainExactly
                        cell.changedRanges.map { it.first to it.last + 1 }
                }
            }
            boldRangesOf(renderedText("fun main() {")).shouldBeEmpty()
            boldRangesOf(renderedText("}")).shouldBeEmpty()
        }
    }

    test("탭을 편 뒤 옮겨진 강조 구간도 같은 토큰을 굵게 덮는다") {
        runComposeUiTest {
            val hunk = singleLineEditHunk().copy(
                lines = listOf(deletedLine("\tvalue = 1", oldLineNumber = 1, changedRanges = listOf(9..9))),
            )
            setContent { ViewerUnderTest(DiffResult.Computed(listOf(hunk))) }

            val rendered = renderedText("    value = 1")

            boldRangesOf(rendered) shouldContainExactly listOf(12 to 13)
            withClue("탭 확장으로 밀린 뒤에도 같은 글자를 덮어야 한다") {
                rendered.text.substring(12, 13) shouldBe "1"
            }
        }
    }

    test("통합·분할 모드가 같은 내용을 각 레이아웃으로 표시한다") {
        runComposeUiTest {
            val state = DiffViewerState()
            setContent { ViewerUnderTest(computed(singleLineEditHunk()), state = state) }

            // 통합 — 삭제와 추가가 세로로 이어진다.
            val unifiedDeleted = onNodeWithText("    val value = 1").getBoundsInRoot()
            val unifiedAdded = onNodeWithText("    val value = 2").getBoundsInRoot()
            unifiedDeleted.top shouldBeLessThan unifiedAdded.top

            onNodeWithText(diffStrings.splitViewMode).performClick()
            waitForIdle()
            state.viewMode shouldBe DiffViewMode.SPLIT

            // 분할 — 같은 두 줄이 좌우로 나란히 놓인다.
            val splitDeleted = onNodeWithText("    val value = 1").getBoundsInRoot()
            val splitAdded = onNodeWithText("    val value = 2").getBoundsInRoot()
            splitDeleted.left shouldBeLessThan splitAdded.left
            splitDeleted.top shouldBe splitAdded.top
        }
    }

    test("이진 파일은 사유 안내가 표시되고 라인이 하나도 그려지지 않는다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(DiffResult.NotComputed(DiffResult.Reason.BINARY)) }

            onNodeWithTag(DiffTags.NOTICE).assertIsDisplayed()
            onNodeWithText(diffStrings.binaryNotice).assertIsDisplayed()
            onAllNodesWithTag(DiffTags.LINE).fetchSemanticsNodes().size shouldBe 0
        }
    }

    test("임계치 초과 파일은 이진 파일과 다른 사유 안내가 표시된다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(DiffResult.NotComputed(DiffResult.Reason.TOO_LARGE)) }

            onNodeWithTag(DiffTags.NOTICE).assertIsDisplayed()
            onNodeWithText(diffStrings.tooLargeNotice).assertIsDisplayed()
        }
    }

    test("변경이 없는 파일은 변경 없음 안내가 표시된다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(DiffResult.Computed(emptyList())) }

            onNodeWithTag(DiffTags.NOTICE).assertIsDisplayed()
            onNodeWithText(diffStrings.noChangesNotice).assertIsDisplayed()
        }
    }

    test("hunk 스테이징 액션은 클릭한 hunk 를 콜백으로만 넘긴다") {
        runComposeUiTest {
            val staged = mutableListOf<DiffHunk>()
            val hunks = listOf(singleLineEditHunk(), unevenHunk())
            setContent { ViewerUnderTest(DiffResult.Computed(hunks), onStageHunk = { staged += it }) }

            onNodeWithTag(DiffTags.stageHunk(0)).performClick()
            waitForIdle()

            staged shouldContainExactly listOf(hunks[0])
        }
    }

    test("hunk 스테이징 액션은 키보드로도 실행된다") {
        runComposeUiTest {
            val staged = mutableListOf<DiffHunk>()
            val hunk = singleLineEditHunk()
            setContent { ViewerUnderTest(computed(hunk), onStageHunk = { staged += it }) }

            onNodeWithTag(DiffTags.stageHunk(0)).requestFocus()
            onNodeWithTag(DiffTags.stageHunk(0)).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            staged shouldContainExactly listOf(hunk)
        }
    }

    test("수만 라인 diff 에서도 화면에 보이는 만큼만 구성된다") {
        runComposeUiTest {
            val lineCount = 20_000
            setContent { ViewerUnderTest(computed(largeHunk(lineCount))) }

            val composed = onAllNodesWithTag(DiffTags.LINE).fetchSemanticsNodes().size
            composed shouldBeGreaterThan 0
            composed intShouldBeLessThan lineCount
            onNodeWithTag(DiffTags.LINES).assertIsDisplayed()
        }
    }

    test("행 key 는 라인 인덱스로 안정적이라 모드를 바꿔도 중복되지 않는다") {
        val hunks = listOf(singleLineEditHunk(), unevenHunk())
        DiffViewMode.entries.forEach { mode ->
            val keys = diffRowsOf(hunks, mode, DEFAULT_TAB_WIDTH).map { it.key }
            keys.distinct().size shouldBe keys.size
        }
    }

    test("다크 모드에서도 추가·삭제 배경이 다크 토큰 값으로 바뀐다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(computed(singleLineEditHunk()), themeMode = ThemeMode.DARK) }

            pixelOf(onNodeWithTag(DiffTags.row(2)).captureToImage().toPixelMap()) shouldBe
                ColorTokens.Dark.deletionSurface
            pixelOf(onNodeWithTag(DiffTags.row(3)).captureToImage().toPixelMap()) shouldBe
                ColorTokens.Dark.additionSurface
        }
    }

    test("뷰어는 hunk 헤더를 hunk 마다 하나씩 그린다") {
        runComposeUiTest {
            setContent { ViewerUnderTest(DiffResult.Computed(listOf(singleLineEditHunk(), unevenHunk()))) }

            onNodeWithTag(DiffTags.hunkHeader(0)).assertIsDisplayed()
            onNodeWithTag(DiffTags.hunkHeader(1)).assertIsDisplayed()
            onAllNodes(hasTestTag(DiffTags.stageHunk(0))).fetchSemanticsNodes().size shouldBe 1
        }
    }
})

private fun computed(hunk: DiffHunk) = DiffResult.Computed(listOf(hunk))

/**
 * 화면이 실제로 그린 본문. semantics 에 실린 `AnnotatedString` 이라 word-level 강조 span 이 함께 온다 —
 * 본문 문자열만 보면 강조가 사라져도 통과하므로 span 까지 집어 온다.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.renderedText(content: String): AnnotatedString =
    onNodeWithText(content, useUnmergedTree = true)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .single()

/** 굵게 강조된 구간의 `start until end` 쌍 목록. */
private fun boldRangesOf(text: AnnotatedString): List<Pair<Int, Int>> =
    text.spanStyles
        .filter { it.item.fontWeight == FontWeight.Bold }
        .map { it.start to it.end }

/** 행 배경 픽셀 하나 — 좌상단은 라인 번호 열이라 배경이 그대로 보인다. */
private fun pixelOf(pixels: androidx.compose.ui.graphics.PixelMap): Color = pixels[1, 1]

@Composable
private fun ViewerUnderTest(
    result: DiffResult,
    state: DiffViewerState = DiffViewerState(),
    themeMode: ThemeMode = ThemeMode.LIGHT,
    onStageHunk: (DiffHunk) -> Unit = {},
) {
    UndineTheme(themeMode = themeMode) {
        CompositionLocalProvider(LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
            DiffViewer(
                result = result,
                state = state,
                onStageHunk = onStageHunk,
                modifier = Modifier.size(VIEWER_WIDTH, VIEWER_HEIGHT),
            )
        }
    }
}
