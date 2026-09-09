package dev.undine.presentation.contextmenu

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.Branch
import dev.undine.domain.BranchTarget
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.domain.ThemeMode
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.graph.CommitGraphView
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.graph.GraphDragDropState
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.graph.GraphTags
import dev.undine.presentation.graph.GraphViewState
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.contextMenu
import dev.undine.presentation.i18n.contextMenuTranslations
import dev.undine.presentation.i18n.graphDragDropTranslations
import dev.undine.presentation.i18n.graphTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.timeTranslations
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext

private val VIEW_WIDTH = 800.dp
private val VIEW_HEIGHT = 400.dp

private val MAIN = RefName("main")
private val FEATURE = RefName("feature/login")
private val REMOTE_MAIN = RefName("origin/main")
private val ANNOTATED_TAG = RefName("v1.0.0")

/** 커밋 1 에 main·feature·origin/main 이, 커밋 2 에 annotated 태그가 붙는다. */
private val HEAD_COMMIT = commitId(1)
private val OLDER_COMMIT = commitId(2)

private val CATALOG = StringCatalog(
    translations = mergeTranslations(
        listOf(timeTranslations, graphTranslations, graphDragDropTranslations, contextMenuTranslations),
    ),
    defaultLocale = DEFAULT_LOCALE,
)
private val COPY = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false).contextMenu

/**
 * 우클릭이 만든 요청을 모으는 배선. 실제 실행 경로와 같은 콜백을 지난다.
 *
 * [currentBranch] 를 `null` 로 주면 detached HEAD 다 — 현재 브랜치 위에서 도는 조작이 수행할
 * 브랜치를 잃는 상태를 그대로 재현한다.
 */
private class MenuHarness(pickedCommit: CommitId? = OLDER_COMMIT, val currentBranch: RefName? = MAIN) {
    val requested = mutableListOf<GraphOperation>()
    val selectedCommits = mutableListOf<CommitId>()
    val state = GraphContextMenuState()

    // 확인창이 열리는 데까지만 본다 — 확인을 누르지 않으므로 실행 코루틴이 시작되지 않는다.
    // 그래서 디스패처를 고르지 않는다.
    private val dragDrop = GraphDragDropState(
        execute = { GraphOperationOutcome.NoChange(MAIN) },
        scope = CoroutineScope(EmptyCoroutineContext),
    )
    private val callbacks = GraphOperationCallbacks(dragDrop)

    val binding = GraphContextMenuBinding(
        state = state,
        selection = { GraphContextSelection(commit = pickedCommit, currentBranch = currentBranch) },
        onOperation = { operation ->
            requested += operation
            callbacks.request(operation)
        },
    )

    /** 확인창까지 도달했는가 — 우클릭이 기존 확인·Undo 경로를 지났다는 증거다. */
    val confirmationChoices: List<GraphOperation> get() = dragDrop.confirmation?.choices.orEmpty()
}

/**
 * 그래프 우클릭 메뉴 — 대상별 항목, 비활성 표시, 닫기 경로, 키보드 경로.
 *
 * 좌표 클릭·OS 자동화에 기대지 않고 실제 컴포저블을 구동한다 (결정 D6).
 */
@OptIn(ExperimentalTestApi::class)
class GraphContextMenuSpec : FunSpec({

    test("커밋 행을 우클릭하면 그 커밋으로 가능한 조작이 메뉴로 열린다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.MENU).assertIsDisplayed()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).assertIsDisplayed()
            // 커밋 대상에 성립하지 않는 조작은 아예 목록에 없다.
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MERGE)).assertDoesNotExist()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.RESET_BRANCH)).assertDoesNotExist()
        }
    }

    test("우클릭은 행 선택을 함께 일으키지 않는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()

            // 누름을 소비하지 않으면 우클릭이 행의 클릭 경로까지 흘러 다른 커밋이 선택된다.
            harness.selectedCommits.shouldBeEmpty()
        }
    }

    test("로컬 브랜치 칩을 우클릭하면 merge·rebase·reset 셋이 나온다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(chipTag(FEATURE), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MERGE)).assertIsEnabled()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.REBASE)).assertIsEnabled()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.RESET_BRANCH)).assertIsEnabled()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).assertDoesNotExist()
        }
    }

    test("원격 브랜치 칩 메뉴에는 로컬 전용 조작인 reset 이 나오지 않는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(chipTag(REMOTE_MAIN), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MERGE)).assertIsDisplayed()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.REBASE)).assertIsDisplayed()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.RESET_BRANCH)).assertDoesNotExist()
        }
    }

    test("실행할 수 없는 항목은 사라지지 않고 사유와 함께 비활성으로 남는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            // annotated 태그는 옮기면 메시지와 tagger 를 잃어 이동할 수 없다.
            onNodeWithTag(chipTag(ANNOTATED_TAG), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()

            val item = onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MOVE_TAG))
            item.assertIsDisplayed()
            item.assertIsNotEnabled()
            onNodeWithContentDescription(
                COPY.blockedItem(COPY.itemMoveTag, COPY.reason(GraphDropRefusal.ANNOTATED_TAG)),
            ).assertIsDisplayed()

            item.performClick()
            waitForIdle()

            // 눌러도 실행 경로로 가지 않는다.
            harness.requested.shouldBeEmpty()
            harness.confirmationChoices.shouldBeEmpty()
        }
    }

    test("detached HEAD 에서 cherry-pick 은 사유와 함께 비활성이고 눌러도 실행되지 않는다") {
        val harness = MenuHarness(currentBranch = null)

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()

            val item = onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK))
            // 사라지지 않는다 — 사라지면 사용자는 cherry-pick 이 없는 기능이라고 읽는다 (결정 D3).
            item.assertIsDisplayed()
            item.assertIsNotEnabled()
            onNodeWithContentDescription(
                COPY.blockedItem(COPY.itemCherryPick, COPY.reason(GraphDropRefusal.NO_CURRENT_BRANCH)),
            ).assertIsDisplayed()

            item.performClick()
            waitForIdle()

            harness.requested.shouldBeEmpty()
            harness.confirmationChoices.shouldBeEmpty()
        }
    }

    test("detached HEAD 에서 브랜치 merge·rebase 는 막히고 reset 은 그대로 실행할 수 있다") {
        val harness = MenuHarness(currentBranch = null)

        runGraph(harness) {
            onNodeWithTag(chipTag(FEATURE), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MERGE)).assertIsNotEnabled()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.REBASE)).assertIsNotEnabled()
            // reset 은 이름으로 지목한 브랜치를 옮기므로 체크아웃과 무관하다.
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.RESET_BRANCH)).assertIsEnabled()

            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.MERGE)).performClick()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.REBASE)).performClick()
            waitForIdle()

            harness.requested.shouldBeEmpty()
            harness.confirmationChoices.shouldBeEmpty()
        }
    }

    test("항목을 고르면 기존 실행 경로로 넘어가고 메뉴가 닫힌다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).performClick()
            waitForIdle()

            harness.requested shouldContainExactly listOf(
                GraphOperation.CherryPick(OLDER_COMMIT, BranchTarget.Current),
            )
            // 드래그&드롭과 같은 확인창을 지난다 — 즉시 실행되지 않는다.
            harness.confirmationChoices shouldContainExactly listOf(
                GraphOperation.CherryPick(OLDER_COMMIT, BranchTarget.Current),
            )
            onNodeWithTag(ContextMenuTags.MENU).assertDoesNotExist()
        }
    }

    test("ESC 는 아무것도 실행하지 않고 메뉴를 닫는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).requestFocus()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK))
                .performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.MENU).assertDoesNotExist()
            harness.requested.shouldBeEmpty()
        }
    }

    test("메뉴 밖 클릭은 메뉴를 닫되 그 클릭이 아래 행으로 전달되지 않는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performMouseInput { rightClick(rowEdge()) }
            waitForIdle()

            // 메뉴는 커서 위치에 뜨므로 좌상단은 메뉴 밖이다.
            onNodeWithTag(ContextMenuTags.SCRIM).performMouseInput { click(Offset(2f, 2f)) }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.MENU).assertDoesNotExist()
            harness.requested.shouldBeEmpty()
            harness.selectedCommits.shouldBeEmpty()
        }
    }

    test("키보드만으로 메뉴를 열고 항목을 실행할 수 있다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).requestFocus()
            onNodeWithTag(GraphTags.row(OLDER_COMMIT)).performKeyInput { pressKey(Key.Menu) }
            waitForIdle()

            onNodeWithTag(ContextMenuTags.MENU).assertIsDisplayed()

            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).requestFocus()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK))
                .performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            harness.requested shouldContainExactly listOf(
                GraphOperation.CherryPick(OLDER_COMMIT, BranchTarget.Current),
            )
        }
    }

    test("메뉴와 항목에 읽어 줄 이름이 있고 문구는 카탈로그에서 온다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(chipTag(FEATURE), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithContentDescription(COPY.menuName).assertIsDisplayed()
            onNodeWithContentDescription(COPY.itemMerge).assertIsDisplayed()
            onNodeWithContentDescription(COPY.itemRebase).assertIsDisplayed()
        }
    }

    test("지목한 대상은 메뉴를 닫은 뒤에도 남아 팔레트가 읽는다") {
        val harness = MenuHarness()

        runGraph(harness) {
            onNodeWithTag(chipTag(FEATURE), useUnmergedTree = true).performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithTag(ContextMenuTags.SCRIM).performMouseInput { click(Offset(2f, 2f)) }
            waitForIdle()

            harness.state.request shouldBe null
            // 팔레트의 graph.merge·graph.rebase 가 활성이 되는 근거가 이 값이다 (결정 D9-2).
            harness.state.selectedTarget shouldBe
                GraphContextTarget.Branch(FEATURE, HEAD_COMMIT, isRemote = false)
        }
    }
})

/** 커밋 둘과 참조 넷이 붙은 그래프를 띄운다. 드래그 배선도 함께 얹어 두 경로가 공존함을 본다. */
/** 칩은 행이 하위 시맨틱을 합치므로 unmerged 트리에서 집는다. */
private fun chipTag(ref: RefName): String = GraphTags.chip(ref.value)

/** 행 오른쪽 끝 — 칩이 아니라 **행**이 우클릭을 받는 자리다 (칩은 자기 메뉴를 연다). */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.MouseInjectionScope.rowEdge(): Offset =
    Offset(width - ROW_EDGE_INSET, height / 2f)

private const val ROW_EDGE_INSET = 8f

@OptIn(ExperimentalTestApi::class)
private fun runGraph(harness: MenuHarness, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
    val state = GraphViewState(
        loadCommitHistory = LoadCommitHistoryUseCase(RecordingHistoryGateway(historyOf())),
        refs = listOf(MAIN),
        pageSize = 2,
    )
    setContent {
        MenuHost {
            CommitGraphView(
                state = state,
                now = FIXED_NOW,
                modifier = Modifier.size(VIEW_WIDTH, VIEW_HEIGHT),
                refIndex = refIndexOf(harness.currentBranch),
                contextMenu = harness.binding,
                onCommitSelected = { commit -> harness.selectedCommits += commit.id },
            )
        }
    }
    waitForIdle()
    block()
}

private fun historyOf(): List<Commit> = listOf(commit(1, 2), commit(2))

/** [currentBranch] 가 `null` 이면 detached HEAD — 어느 브랜치도 체크아웃돼 있지 않다. */
private fun refIndexOf(currentBranch: RefName? = MAIN): CommitRefIndex = CommitRefIndex.of(
    branches = listOf(
        Branch(
            MAIN,
            HEAD_COMMIT,
            isCurrent = currentBranch == MAIN,
            isRemote = false,
            upstream = null,
            ahead = 0,
            behind = 0,
        ),
        Branch(FEATURE, HEAD_COMMIT, isCurrent = false, isRemote = false, upstream = null, ahead = 0, behind = 0),
        Branch(REMOTE_MAIN, HEAD_COMMIT, isCurrent = false, isRemote = true, upstream = null, ahead = 0, behind = 0),
    ),
    tags = listOf(Tag(ANNOTATED_TAG, OLDER_COMMIT, isAnnotated = true, message = "릴리즈", tagger = null)),
    currentBranch = currentBranch,
)

@Composable
private fun MenuHost(content: @Composable () -> Unit) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}
