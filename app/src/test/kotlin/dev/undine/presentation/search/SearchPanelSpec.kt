package dev.undine.presentation.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.search.SearchProgress
import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.search.CommitSearchCriteria
import dev.undine.domain.search.SEARCH_ZONE
import dev.undine.domain.search.commitOf
import dev.undine.domain.search.hashOf
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.search
import dev.undine.presentation.i18n.searchTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

private val REFS = listOf(RefName("refs/heads/main"))

private val FIRST = commitOf(id = hashOf("aa1"), message = "fix login timeout")
private val SECOND = commitOf(id = hashOf("bb2"), message = "fix login redirect")

/** `search.*` 는 아직 내장 목록에 없어 자기 맵으로 카탈로그를 만든다 (wave 3 결정 A3). */
private val CATALOG = StringCatalog(translations = searchTranslations, defaultLocale = DEFAULT_LOCALE)

private val TEXTS = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false).search

private fun openFlowOf(channel: Channel<SearchProgress>): Flow<SearchProgress> = channel.receiveAsFlow()

/** 순회가 커밋 하나를 찾았다. */
private fun Channel<SearchProgress>.sendMatch(commit: Commit) = trySend(SearchProgress.Match(commit))

private fun stateWith(useCase: SearchCommitsUseCase): SearchState = SearchState(
    searchCommits = useCase,
    scope = CoroutineScope(Dispatchers.Unconfined),
    refs = REFS,
    zone = SEARCH_ZONE,
)

/** 검색 화면 — 진행 표시·0건 구분·점진 추가·오래된 결과 차단·키보드 경로. */
@OptIn(ExperimentalTestApi::class)
class SearchPanelSpec : FunSpec({

    test("조건이 없으면 안내 문구만 보이고 결과 목록을 그리지 않는다") {
        runComposeUiTest {
            setContent { SearchPanelUnderTest(stateWith(mockk())) }

            onNodeWithTag(SearchTags.IDLE_STATE).assertIsDisplayed()
            onNodeWithText(TEXTS.idle).assertIsDisplayed()
            onNodeWithTag(SearchTags.RESULT_LIST).assertDoesNotExist()
            onNodeWithTag(SearchTags.SEARCHING).assertDoesNotExist()
        }
    }

    test("검색 중에는 진행 표시가 보이고 0건 안내는 보이지 않는다") {
        runComposeUiTest {
            val channel = Channel<SearchProgress>(Channel.UNLIMITED)
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns openFlowOf(channel)
            val state = stateWith(useCase)
            setContent { SearchPanelUnderTest(state) }

            typeMessage("fix")

            onNodeWithTag(SearchTags.SEARCHING).assertIsDisplayed()
            onNodeWithText(TEXTS.searching).assertIsDisplayed()
            // 진행 막대는 공용 컴포넌트를 그대로 쓴다 — 순회 진행률을 눈으로 알린다.
            onNodeWithTag(SearchTags.PROGRESS).assertExists()
            onNodeWithTag(SearchTags.EMPTY_STATE).assertDoesNotExist()

            channel.close()
            waitForIdle()

            onNodeWithTag(SearchTags.PROGRESS).assertDoesNotExist()
            onNodeWithTag(SearchTags.SEARCHING).assertDoesNotExist()
            onNodeWithTag(SearchTags.EMPTY_STATE).assertIsDisplayed()
            onNodeWithText(TEXTS.noResults).assertIsDisplayed()
        }
    }

    test("결과는 전체 순회가 끝나기 전에 한 건씩 목록에 추가된다") {
        runComposeUiTest {
            val channel = Channel<SearchProgress>(Channel.UNLIMITED)
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns openFlowOf(channel)
            setContent { SearchPanelUnderTest(stateWith(useCase)) }

            typeMessage("fix")
            channel.sendMatch(FIRST)
            waitForIdle()

            onNodeWithTag(SearchTags.RESULT_LIST).assertIsDisplayed()
            onNodeWithTag(SearchTags.row(FIRST.id)).assertIsDisplayed()
            onNodeWithTag(SearchTags.row(SECOND.id)).assertDoesNotExist()
            // 아직 순회가 끝나지 않았으므로 진행 표시가 결과와 함께 남아 있다.
            onNodeWithTag(SearchTags.SEARCHING).assertIsDisplayed()

            channel.sendMatch(SECOND)
            waitForIdle()

            onNodeWithTag(SearchTags.row(FIRST.id)).assertIsDisplayed()
            onNodeWithTag(SearchTags.row(SECOND.id)).assertIsDisplayed()
        }
    }

    test("검색어를 바꾸면 이전 검색의 늦은 결과가 화면에 나타나지 않는다") {
        runComposeUiTest {
            val staleChannel = Channel<SearchProgress>(Channel.UNLIMITED)
            val freshChannel = Channel<SearchProgress>(Channel.UNLIMITED)
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(REFS, CommitSearchCriteria(message = "fix", zone = SEARCH_ZONE)) } returns
                openFlowOf(staleChannel)
            every {
                useCase.execute(REFS, CommitSearchCriteria(message = "fix login", zone = SEARCH_ZONE))
            } returns openFlowOf(freshChannel)
            setContent { SearchPanelUnderTest(stateWith(useCase)) }

            typeMessage("fix")
            typeMessage(" login")

            staleChannel.sendMatch(FIRST)
            waitForIdle()

            onNodeWithTag(SearchTags.row(FIRST.id)).assertDoesNotExist()

            freshChannel.sendMatch(SECOND)
            waitForIdle()

            onNodeWithTag(SearchTags.row(SECOND.id)).assertIsDisplayed()
            onNodeWithTag(SearchTags.row(FIRST.id)).assertDoesNotExist()
        }
    }

    test("위아래 방향키로 결과를 오가고 Enter 로 커밋을 고른다") {
        runComposeUiTest {
            val channel = Channel<SearchProgress>(Channel.UNLIMITED)
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns openFlowOf(channel)
            var selected: Commit? = null
            setContent { SearchPanelUnderTest(stateWith(useCase), onCommitSelected = { selected = it }) }

            typeMessage("fix")
            channel.sendMatch(FIRST)
            channel.sendMatch(SECOND)
            waitForIdle()

            pressOnMessageField(Key.Enter)
            selected shouldBe FIRST

            pressOnMessageField(Key.DirectionDown)
            pressOnMessageField(Key.Enter)
            selected shouldBe SECOND

            pressOnMessageField(Key.DirectionUp)
            pressOnMessageField(Key.Enter)
            selected shouldBe FIRST
        }
    }

    test("조건 지우기를 누르면 입력과 결과가 비워지고 안내 문구로 돌아간다") {
        runComposeUiTest {
            val channel = Channel<SearchProgress>(Channel.UNLIMITED)
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns openFlowOf(channel)
            setContent { SearchPanelUnderTest(stateWith(useCase)) }

            typeMessage("fix")
            channel.sendMatch(FIRST)
            waitForIdle()
            onNodeWithTag(SearchTags.row(FIRST.id)).assertIsDisplayed()

            onNodeWithTag(SearchTags.CLEAR).performClick()
            waitForIdle()

            onNodeWithTag(SearchTags.row(FIRST.id)).assertDoesNotExist()
            onNodeWithTag(SearchTags.IDLE_STATE).assertIsDisplayed()
        }
    }

    test("날짜 형식이 잘못되면 그 축이 잘못된 입력임을 화면이 알린다") {
        runComposeUiTest {
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns openFlowOf(Channel(Channel.UNLIMITED))
            setContent { SearchPanelUnderTest(stateWith(useCase)) }

            onNodeWithTag(SearchTags.INVALID_DATE).assertDoesNotExist()

            onNodeWithTag(SearchTags.field(SearchField.SINCE)).requestFocus()
            onNodeWithTag(SearchTags.field(SearchField.SINCE)).performTextInput("2026-13-99")
            waitForIdle()

            onNodeWithTag(SearchTags.INVALID_DATE).assertIsDisplayed()
            onNodeWithText(TEXTS.invalidDate).assertIsDisplayed()
        }
    }

    test("검색 실패는 0건 안내가 아니라 실패 문구로 표시된다") {
        runComposeUiTest {
            val useCase = mockk<SearchCommitsUseCase>()
            every { useCase.execute(any(), any()) } returns
                flow { throw UndineException.GitOperationFailed("load") }
            setContent { SearchPanelUnderTest(stateWith(useCase)) }

            typeMessage("fix")

            onNodeWithTag(SearchTags.FAILED).assertIsDisplayed()
            onNodeWithText(TEXTS.failed).assertIsDisplayed()
            onNodeWithTag(SearchTags.EMPTY_STATE).assertDoesNotExist()
        }
    }
})

/** 메시지 입력칸에 글자를 넣는다 — 상태를 직접 부르지 않고 실제 입력 경로를 지난다. */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.typeMessage(text: String) {
    onNodeWithTag(SearchTags.field(SearchField.MESSAGE)).requestFocus()
    onNodeWithTag(SearchTags.field(SearchField.MESSAGE)).performTextInput(text)
    waitForIdle()
}

/** 입력칸에 포커스가 있는 상태로 키를 누른다 — 패널이 먼저 가로채는 경로를 검증한다. */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.pressOnMessageField(key: Key) {
    onNodeWithTag(SearchTags.field(SearchField.MESSAGE)).requestFocus()
    onNodeWithTag(SearchTags.field(SearchField.MESSAGE)).performKeyInput { pressKey(key) }
    waitForIdle()
}

@Composable
private fun SearchPanelUnderTest(
    state: SearchState,
    onCommitSelected: (Commit) -> Unit = {},
) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
            SearchPanel(state = state, modifier = Modifier, onCommitSelected = onCommitSelected)
        }
    }
}
