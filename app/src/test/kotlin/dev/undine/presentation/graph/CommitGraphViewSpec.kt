package dev.undine.presentation.graph

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.domain.Branch
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.presentation.design.ColorTokens
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.GraphKeys
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.StringKey
import dev.undine.presentation.i18n.graphTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.timeTranslations
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import dev.undine.testsupport.commitIdOf
import dev.undine.testsupport.commitWithId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale

private val REFS = listOf(RefName("refs/heads/main"))
private val VIEW_WIDTH = 800.dp
private val VIEW_HEIGHT = 400.dp

/** 기본 로케일의 `graph.head` 번역값. 이 상수가 아니라 번역 맵이 표시의 기준이다. */
private val HEAD_LABEL: String = graphTranslations.getValue(DEFAULT_LOCALE).getValue(GraphKeys.head)
private const val LARGE_HISTORY = 1000
private const val PAGED_PAGE_SIZE = 6

private const val VIEW_SOURCE_PATH =
    "src/main/kotlin/dev/undine/presentation/graph/CommitGraphView.kt"

/** `items(key = ...)` 가 커밋 해시를 쓰는지 — 렌더링으로는 key 자체를 볼 수 없어 소스로 확인한다. */
private val COMMIT_KEY_PATTERN = Regex("""key\s*=\s*\{[^}]*commit\.id""")

private val ROW_TAG_MATCHER = SemanticsMatcher("커밋 행") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(GraphTags.ROW_PREFIX) == true
}

private fun graphState(
    gateway: RecordingHistoryGateway,
    pageSize: Int,
): GraphViewState = GraphViewState(
    loadCommitHistory = LoadCommitHistoryUseCase(gateway),
    refs = REFS,
    pageSize = pageSize,
)

/** 커밋 그래프 화면 — 목록 가상화·행 내용·레인 렌더링·빈/실패 상태·선택·페이징. */
@OptIn(ExperimentalTestApi::class)
class CommitGraphViewSpec : FunSpec({

    test("커밋 1000건을 실어도 보이는 행만 구성되고 먼 인덱스로 스크롤하면 그 커밋이 보인다") {
        val commits = (1..LARGE_HISTORY).map { commit(it, it + 1) }
        val state = graphState(RecordingHistoryGateway(commits), pageSize = LARGE_HISTORY)

        runComposeGraph(state) {
            onNodeWithTag(GraphTags.row(commitId(1))).assertIsDisplayed()

            val composedRows = onAllNodes(ROW_TAG_MATCHER).fetchSemanticsNodes().size
            composedRows shouldBeGreaterThan 0
            // 1000건 전체를 구성하면 스크롤 프레임이 무너진다 — LazyColumn 가상화가 살아 있어야 한다.
            composedRows shouldBeLessThan LARGE_HISTORY / 10

            onNodeWithTag(GraphTags.LIST).performScrollToIndex(500)
            waitForIdle()
            onNodeWithTag(GraphTags.row(commitId(501))).assertIsDisplayed()
        }
    }

    test("LazyColumn 항목 key 는 커밋 해시로 지정된다") {
        val source = File(VIEW_SOURCE_PATH)

        source.isFile shouldBe true
        COMMIT_KEY_PATTERN.containsMatchIn(source.readText()) shouldBe true
    }

    test("행에 커밋 요약·작성자·상대 시각·짧은 해시가 표시된다") {
        val id = commitIdOf("abc1234")
        val commits = listOf(commitWithId(id, message = "그래프 뷰를 추가한다\n\n본문은 표시하지 않는다"))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 1)

        runComposeGraph(state) {
            onNodeWithText("그래프 뷰를 추가한다").assertIsDisplayed()
            onNodeWithText("Undine").assertIsDisplayed()
            onNodeWithText("방금 전").assertIsDisplayed()
            onNodeWithText("abc1234").assertIsDisplayed()
        }
    }

    test("HEAD·브랜치·태그가 해당 커밋 행에 칩으로 표시된다") {
        val commits = listOf(commit(1, 2), commit(2))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 2)

        runComposeGraph(state, refIndex = headBranchTagIndex()) {
            onNodeWithText(HEAD_LABEL).assertIsDisplayed()
            onNodeWithText("main").assertIsDisplayed()
            onNodeWithText("feature").assertIsDisplayed()
            onNodeWithText("v1.0.0").assertIsDisplayed()
        }
    }

    test("HEAD 칩 라벨은 하드코딩이 아니라 graph.head 번역에서 온다") {
        // 번역만 바꿔서 화면 문구가 따라 바뀌는지 본다 — 라벨이 코드에 박혀 있으면 이 테스트가 깨진다.
        val localizedHead = "현재 위치"
        val commits = listOf(commit(1, 2), commit(2))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 2)

        runComposeGraph(
            state = state,
            refIndex = headBranchTagIndex(),
            translationOverrides = mapOf(DEFAULT_LOCALE to mapOf(GraphKeys.head to localizedHead)),
        ) {
            onNodeWithText(localizedHead).assertIsDisplayed()
            onAllNodesWithText(HEAD_LABEL).fetchSemanticsNodes().size shouldBe 0
            // 브랜치·태그 칩은 참조 이름을 그대로 쓰므로 번역에 영향받지 않는다.
            onNodeWithText("main").assertIsDisplayed()
        }
    }

    test("병합 커밋 행에 두 부모를 잇는 병합선이 그려진다") {
        // 1 = 2·3 의 병합. 3 은 레인 1 에 놓이므로 병합선이 레인 1 아래로 이어져야 한다.
        val commits = listOf(commit(1, 2, 3), commit(2, 4), commit(3, 4), commit(4))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 4)

        runComposeGraph(state) {
            val lanes = lanePixels(commitId(1))
            val laneWidth = lanes.width / 2f

            lanes[laneCenter(laneWidth, lane = 1), lanes.height - 2] shouldNotBe LIGHT_BACKGROUND
            lanes[laneCenter(laneWidth, lane = 0), lanes.height - 2] shouldNotBe LIGHT_BACKGROUND
        }
    }

    test("행을 통과만 하는 선은 행 위아래로 끊기지 않고 이어진다") {
        val commits = listOf(commit(1, 4), commit(2, 3), commit(3, 4), commit(4))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 4)

        runComposeGraph(state) {
            val lanes = lanePixels(commitId(2))
            val laneWidth = lanes.width / 2f

            lanes[laneCenter(laneWidth, lane = 0), 1] shouldNotBe LIGHT_BACKGROUND
            lanes[laneCenter(laneWidth, lane = 0), lanes.height - 2] shouldNotBe LIGHT_BACKGROUND
        }
    }

    test("페이지 밖 부모로 이어지는 연결은 항목이 남되 화면 밖으로 선을 잇지 않는다") {
        // 마지막 커밋 3 의 부모 4 는 이력에 없다 — toLane 이 없으므로 아래로 선을 그리면 안 된다.
        val commits = listOf(commit(1, 2), commit(2, 3), commit(3, 4))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 2)

        runComposeGraph(state) {
            val lastRow = state.rows.last()
            lastRow.commit.id shouldBe commitId(3)
            // 항목은 제거되지 않고 남는다 — Commit.parents 와 1:1 대응이 깨지면 안 된다.
            lastRow.row.parents.size shouldBe 1

            val lanes = lanePixels(commitId(3))
            val laneWidth = lanes.width.toFloat()

            lanes[laneCenter(laneWidth, lane = 0), lanes.height - 2] shouldBe LIGHT_BACKGROUND
        }
    }

    test("커밋이 0건이면 빈 상태 안내가 표시되고 크래시하지 않는다") {
        val state = graphState(RecordingHistoryGateway(), pageSize = 4)

        runComposeGraph(state) {
            onNodeWithTag(GraphTags.ROOT).assertIsDisplayed()
            onNodeWithTag(GraphTags.EMPTY).assertIsDisplayed()
            onAllNodes(ROW_TAG_MATCHER).fetchSemanticsNodes().size shouldBe 0
        }
    }

    test("이력 로딩 실패는 빈 상태가 아니라 구분되는 실패 상태로 표시된다") {
        val gateway = RecordingHistoryGateway(failure = UndineException.GitOperationFailed("history"))
        val state = graphState(gateway, pageSize = 4)

        runComposeGraph(state) {
            onNodeWithTag(GraphTags.ERROR).assertIsDisplayed()
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, GraphTags.EMPTY))
                .fetchSemanticsNodes().size shouldBe 0
        }
    }

    test("행을 선택하면 상태 홀더에 반영되고 셸로 전달된다") {
        val commits = listOf(commit(1, 2), commit(2))
        val state = graphState(RecordingHistoryGateway(commits), pageSize = 2)
        val selected = mutableListOf<Commit>()

        runComposeGraph(state, onCommitSelected = { selected += it }) {
            onNodeWithTag(GraphTags.row(commitId(2))).performClick()
            waitForIdle()

            state.selectedCommitId shouldBe commitId(2)
            state.selectedCommit?.id shouldBe commitId(2)
            selected.map { it.id } shouldContainExactly listOf(commitId(2))
        }
    }

    test("하단에 도달하면 같은 페이지를 두 번 요청하지 않고 이어서 다음 페이지를 부른다") {
        val commits = (1..30).map { commit(it, it + 1) }
        val gateway = RecordingHistoryGateway(commits)
        val state = graphState(gateway, pageSize = 6)

        runComposeGraph(state) {
            val offsets = gateway.requests.map { it.offset }
            offsets.first() shouldBe 0
            offsets shouldContainExactly offsets.distinct()

            val beforeScroll = gateway.requests.size
            onNodeWithTag(GraphTags.LIST).performScrollToIndex(state.rows.size - 1)
            waitForIdle()

            gateway.requests.size shouldBeGreaterThan beforeScroll
            gateway.requests.map { it.offset } shouldContainExactly
                gateway.requests.map { it.offset }.distinct()
        }
    }

    test("하단 페이징 요청이 취소되면 이미 쌓인 행과 Loaded 상태가 그대로 남는다") {
        // 빠른 스크롤로 화면이 떠나면 진행 중인 다음 페이지 요청이 취소된다. 그때 첫 페이지가
        // 사라지거나 실패·빈 상태로 뒤바뀌면 안 된다.
        val gate = CompletableDeferred<Unit>()
        val gateway = RecordingHistoryGateway(
            commits = (1..30).map { commit(it, it + 1) },
            gate = gate,
            gateFrom = 1,
        )
        val state = graphState(gateway, pageSize = PAGED_PAGE_SIZE)

        runComposeUiTest {
            var attached by mutableStateOf(true)
            setContent {
                GraphHost {
                    if (attached) {
                        CommitGraphView(
                            state = state,
                            modifier = Modifier.size(VIEW_WIDTH, VIEW_HEIGHT),
                            now = FIXED_NOW,
                        )
                    }
                }
            }
            waitForIdle()

            // 첫 페이지는 붙었고, 두 번째 요청은 gate 에서 멈춰 진행 중이다.
            state.rows shouldHaveSize PAGED_PAGE_SIZE
            state.status shouldBe GraphLoadStatus.Loaded
            gateway.requests.map { it.offset } shouldContainExactly listOf(0, PAGED_PAGE_SIZE)

            attached = false // 컴포지션 이탈 = 진행 중 페이지 요청 취소
            waitForIdle()
            gate.complete(Unit)
            waitForIdle()

            // 취소된 요청의 결과가 뒤늦게 붙지 않고, 상태도 성공·빈 결과로 바뀌지 않는다.
            state.rows shouldHaveSize PAGED_PAGE_SIZE
            state.status shouldBe GraphLoadStatus.Loaded
            gateway.requests.map { it.offset } shouldContainExactly listOf(0, PAGED_PAGE_SIZE)
        }
    }
})

/** HEAD·브랜치·태그가 모두 붙은 색인. HEAD 는 `main`(커밋 1), 태그는 커밋 2 에 붙는다. */
private fun headBranchTagIndex(): CommitRefIndex = CommitRefIndex.of(
    branches = listOf(
        Branch(RefName("main"), commitId(1), true, false, null, 0, 0),
        Branch(RefName("feature"), commitId(2), false, false, null, 0, 0),
    ),
    tags = listOf(Tag(RefName("v1.0.0"), commitId(2), false, null, null)),
    currentBranch = RefName("main"),
)

private val LIGHT_BACKGROUND: Color = ColorTokens.Light.background

/** 한 행의 레인 캔버스 픽셀. 행은 semantics 를 병합하므로 unmerged 트리에서 집는다. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.lanePixels(commit: CommitId): PixelMap =
    onNodeWithTag(GraphTags.lanes(commit), useUnmergedTree = true).captureToImage().toPixelMap()

/** 픽셀 좌표계에서 레인 중심 x. 이미지 폭으로 레인 폭을 역산해 화면 밀도에 의존하지 않는다. */
private fun laneCenter(laneWidthPx: Float, lane: Int): Int =
    GraphLaneGeometry.laneCenterX(lane, laneWidthPx).toInt()

/** 테마·문자열을 갖춘 최소 호스트에서 그래프 뷰를 띄운다. graph 키는 자기 맵으로 공급한다 (결정 A3). */
@OptIn(ExperimentalTestApi::class)
private fun runComposeGraph(
    state: GraphViewState,
    refIndex: CommitRefIndex = CommitRefIndex.EMPTY,
    onCommitSelected: (Commit) -> Unit = {},
    translationOverrides: Map<Locale, Map<StringKey, String>> = emptyMap(),
    block: ComposeUiTest.() -> Unit,
) = runComposeUiTest {
    setContent {
        GraphHost(translationOverrides) {
            CommitGraphView(
                state = state,
                modifier = Modifier.size(VIEW_WIDTH, VIEW_HEIGHT),
                refIndex = refIndex,
                now = FIXED_NOW,
                onCommitSelected = onCommitSelected,
            )
        }
    }
    waitForIdle()
    block()
}

@Composable
private fun GraphHost(
    translationOverrides: Map<Locale, Map<StringKey, String>> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val catalog = StringCatalog(
        translations = mergeTranslations(
            listOf(timeTranslations, graphTranslations, translationOverrides),
        ),
        defaultLocale = DEFAULT_LOCALE,
    )
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}
