package dev.undine.presentation.contextmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.ThemeMode
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.graph.GraphDragDropState
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.graph.graphOperationCommands
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.contextMenuTranslations
import dev.undine.presentation.i18n.graphDragDrop
import dev.undine.presentation.i18n.graphDragDropTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.sidebarTranslations
import dev.undine.presentation.sidebar.SidebarMergeBinding
import dev.undine.presentation.sidebar.SidebarStateHarness
import dev.undine.presentation.sidebar.SidebarTags
import dev.undine.presentation.sidebar.SidebarTree
import dev.undine.testsupport.commitId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext

private val MAIN = RefName("main")
private val FEATURE = RefName("feature/login")
private val BRANCH_HEAD: CommitId = commitId(1)
private val PICKED: CommitId = commitId(2)

private val SURFACE_WIDTH = 480.dp
private val SURFACE_HEIGHT = 600.dp

/** 세 진입점이 모두 지목하는 **같은 대상** — 대상이 갈리면 답이 갈려도 판정 탓인지 알 수 없다. */
private val LOCAL_BRANCH = GraphContextTarget.Branch(FEATURE, BRANCH_HEAD, isRemote = false)

/** 로컬 브랜치가 내는 세 조작 (결정 D10). */
private val BRANCH_KINDS = listOf(
    GraphOperationKind.MERGE,
    GraphOperationKind.REBASE,
    GraphOperationKind.RESET_BRANCH,
)

private val ATTACHED = GraphContextSelection(commit = PICKED, currentBranch = MAIN)
private val DETACHED = GraphContextSelection(commit = PICKED, currentBranch = null)

private val CATALOG = StringCatalog(
    translations = mergeTranslations(
        listOf(sidebarTranslations, graphDragDropTranslations, contextMenuTranslations),
    ),
    defaultLocale = DEFAULT_LOCALE,
)

/** 사이드바 행이 그 브랜치를 [LOCAL_BRANCH] 와 같은 대상으로 만들도록 head 를 맞춘다. */
private val FEATURE_ROW = Branch(
    name = FEATURE,
    target = BRANCH_HEAD,
    isCurrent = false,
    isRemote = false,
    upstream = null,
    ahead = 0,
    behind = 0,
)

/**
 * **가드가 한 곳에 있는가.** 컨텍스트 메뉴·팔레트·사이드바 병합이 같은 판정을 읽는지 본다.
 *
 * 1차 수리는 detached HEAD 가드를 컨텍스트 메뉴에만 넣었고, 팔레트와 사이드바 merge 배선은 각자
 * 판정해 그 제약을 우회했다. 진입점별 테스트만 세 개 두면 **다음 진입점이 추가될 때 또 빠진다** —
 * 그래서 세 진입점을 한 테스트에서 같은 기준에 맞춘다 (결정 D17·D18).
 *
 * 같은 모양의 선례가 있다: `GraphOperationKeyboardEquivalenceSpec` 이 키보드와 드래그가 같은 경로를
 * 타는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
class GraphOperationGuardEquivalenceSpec : FunSpec({

    test("세 진입점이 실제로 실행하는 조작은 공용 판정이 허용한 것과 정확히 같다") {
        listOf(ATTACHED, DETACHED).forEach { selection ->
            // 기대값을 손으로 적지 않는다 — 공용 판정이 기준이므로, 판정을 고치면 세 진입점의
            // 기대값이 함께 움직인다. 한쪽만 고친 상태로는 통과할 수 없다.
            val allowed = graphMenuEntriesFor(LOCAL_BRANCH, selection)
                .filter { entry -> entry.enabled }
                .map { entry -> entry.operation }

            withClue("팔레트 / currentBranch=${selection.currentBranch}") {
                paletteRuns(selection) shouldContainExactly allowed
            }
            withClue("컨텍스트 메뉴 / currentBranch=${selection.currentBranch}") {
                contextMenuRuns(selection) shouldContainExactly allowed
            }
            // 사이드바가 낼 수 있는 것은 병합 하나다 — 그 하나가 같은 판정을 따르는지 본다.
            withClue("사이드바 병합 / currentBranch=${selection.currentBranch}") {
                sidebarMergeRuns(selection) shouldContainExactly allowed.filterIsInstance<GraphOperation.Merge>()
            }
        }
    }
})

/** 팔레트가 다섯 명령을 모두 눌렸을 때 실제로 요청되는 조작. */
private fun paletteRuns(selection: GraphContextSelection): List<GraphOperation> {
    val callbacks = GraphOperationCallbacks(dragDropState())
    val copy = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false).graphDragDrop
    graphOperationCommands(callbacks, copy) { graphMenuEntriesForPicked(LOCAL_BRANCH, selection) }
        .forEach { command -> command.action() }
    return callbacks.lastRequested
}

/** 우클릭 메뉴의 세 항목을 차례로 눌렀을 때 실제로 요청되는 조작. */
@OptIn(ExperimentalTestApi::class)
private fun contextMenuRuns(selection: GraphContextSelection): List<GraphOperation> {
    val requested = mutableListOf<GraphOperation>()
    val state = GraphContextMenuState()
    val binding = GraphContextMenuBinding(
        state = state,
        selection = { selection },
        onOperation = { operation -> requested += operation },
    )

    runComposeUiTest {
        setContent {
            SurfaceHost {
                Box(modifier = Modifier.size(SURFACE_WIDTH, SURFACE_HEIGHT)) {
                    GraphContextMenuOverlay(binding)
                }
            }
        }
        BRANCH_KINDS.forEach { kind ->
            // 항목을 고르면 메뉴가 닫히므로 종류마다 다시 연다 — 막힌 항목은 닫히지 않는다.
            runOnIdle { state.open(LOCAL_BRANCH, Offset.Zero) }
            waitForIdle()
            onNodeWithTag(ContextMenuTags.item(kind)).performClick()
            waitForIdle()
        }
    }
    return requested
}

/** 사이드바 브랜치 행의 병합 항목을 눌렀을 때 실제로 요청되는 조작. */
@OptIn(ExperimentalTestApi::class)
private fun sidebarMergeRuns(selection: GraphContextSelection): List<GraphOperation> {
    val requested = mutableListOf<GraphOperation>()
    // 화면 배선과 같은 방식이다 — 가용성을 스텁으로 흉내내면 진입점이 판정을 우회해도 초록불이 된다.
    val binding = SidebarMergeBinding(
        entryOf = { branch ->
            graphMenuEntryOf(
                kind = GraphOperationKind.MERGE,
                target = GraphContextTarget.Branch(branch.name, branch.target, branch.isRemote),
                selection = selection,
            )
        },
        onRequest = { operation -> requested += operation },
    )
    val state = SidebarStateHarness().loaded(
        SidebarRefs(branches = listOf(FEATURE_ROW), tags = emptyList(), stashes = emptyList()),
    )

    runComposeUiTest {
        setContent {
            SurfaceHost {
                SidebarTree(
                    state = state,
                    merge = binding,
                    modifier = Modifier.size(SURFACE_WIDTH, SURFACE_HEIGHT),
                )
            }
        }
        onNodeWithTag(SidebarTags.menuButton(FEATURE_ROW)).performClick()
        waitForIdle()
        onNodeWithTag(SidebarTags.MENU_MERGE).performClick()
        waitForIdle()
    }
    return requested
}

@Composable
private fun SurfaceHost(content: @Composable () -> Unit) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}

private fun dragDropState(): GraphDragDropState = GraphDragDropState(
    // 확인창이 열리는 데까지만 본다 — 확인을 누르지 않으므로 실행 코루틴이 시작되지 않는다.
    execute = { GraphOperationOutcome.NoChange(MAIN) },
    scope = CoroutineScope(EmptyCoroutineContext),
)
