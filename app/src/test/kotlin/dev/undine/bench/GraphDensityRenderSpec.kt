package dev.undine.bench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.domain.Branch
import dev.undine.domain.RepositoryPath
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.presentation.design.ScreenshotRenderer
import dev.undine.presentation.graph.CommitGraphView
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.graph.GraphLaneGeometry
import dev.undine.presentation.graph.GraphLoadStatus
import dev.undine.presentation.graph.GraphViewState
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.RecordingHistoryGateway
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan as shouldBeLongerThan
import io.kotest.matchers.shouldBe

/** 렌더 결과 파일 이름. `app/build/screenshots/` 에 남는다. */
private const val RENDER_NAME = "graph-bench-density"

/** [ScreenshotRenderer] 가 쓰는 밀도. 픽셀 크기를 dp 에서 되돌릴 때 필요하다. */
private const val SCREENSHOT_DENSITY = 2f

/** 가로 폭(px). 레인 열·해시·메시지·배지가 한 줄에 함께 보여야 밀도를 판단할 수 있다. */
private const val RENDER_WIDTH = 1200

/**
 * 세로 높이(px). **행 수에서 거꾸로 정한다** — 판정한 행([DENSITY_VISIBLE_ROWS])과 화면에 그려지는
 * 행이 어긋나면, 배지가 있다고 단언해 놓고 화면 밖에 있는 그림을 남기게 된다.
 */
private val RENDER_HEIGHT: Int =
    (DENSITY_VISIBLE_ROWS * GraphLaneGeometry.ROW_HEIGHT.value * SCREENSHOT_DENSITY).toInt()

/** 구간을 고르기 위해 미리 읽어 둘 커밋 수. 이 안에서 배지가 보이는 구간을 찾는다. */
private const val PRELOAD_LIMIT = 400

/**
 * 실제 벤치 저장소의 이력으로 그래프를 렌더해 **사람이 밀도를 볼 재료**를 남긴다 (UND-89).
 *
 * `ScreenshotRenderSpec` 의 그래프 렌더는 손으로 만든 6커밋 픽스처를 쓴다 — 레인이 둘뿐이라
 * 여러 레인이 열린 화면이 읽히는지는 그 그림으로 알 수 없다. 여기서는 UND-88 의 벤치 저장소를
 * 데이터원으로 써서 한 장을 더 남긴다.
 *
 * **읽기를 렌더보다 먼저 끝낸다.** [ScreenshotRenderer] 의 프레임 루프는 `Dispatchers.IO` 의 로드를
 * 기다려 주지 않아, 실제 Gateway 를 그대로 물리면 "이력을 불러오는 중" 화면이 찍힌다. 데이터는
 * 실제 저장소 것을 쓰되 렌더 시점에는 즉시 응답하는 대역([RecordingHistoryGateway])만 남긴다.
 *
 * **색 대비·간격을 픽셀로 판정하지 않는다.** 임계값을 만들면 그 숫자가 디자인 결정을 대신한다.
 * 여기서 단언하는 것은 "볼 것이 실제로 화면에 있는가" 뿐이다 — 커밋 행, 레인
 * [DENSITY_MINIMUM_LANES] 개 이상, ref 배지 1개 이상.
 *
 * 벤치 저장소가 있을 때만 돈다 ([BenchRepositoryPresent]).
 *
 * ```bash
 * .agent/scripts/make-bench-repo.sh --commits 26000 --branches 800 --output /tmp/undine-bench
 * UNDINE_BENCH_REPO=/tmp/undine-bench ./gradlew :app:test --tests '*GraphDensityRender*'
 * ```
 */
@EnabledIf(BenchRepositoryPresent::class)
class GraphDensityRenderSpec : FunSpec({

    val repositoryPath = benchRepositoryPath()
    val gitAccess = GitAccess()
    val historyGateway = HistoryGatewayImpl(gitAccess)
    val refGateway = RefGatewayImpl(gitAccess)

    beforeSpec {
        val located = requireNotNull(repositoryPath) {
            "$BENCH_REPOSITORY_ENV 가 가리키는 벤치 저장소를 찾지 못했습니다"
        }
        gitAccess.open(RepositoryPath(located.toString())) { }
    }

    afterSpec { gitAccess.close() }

    test("실제 벤치 저장소의 여러 레인과 ref 배지가 함께 보이는 구간을 PNG 로 남긴다") {
        val branches = refGateway.listBranches()
        val tags = refGateway.listTags()
        // main 하나만 넣으면 레인이 둘뿐이라 이 렌더의 목적이 사라진다 — 전체 브랜치를 넣는다.
        branches.size shouldBeGreaterThan 1

        val refIndex = CommitRefIndex.of(branches, tags, currentBranchOf(branches))
        val refs = branches.map { it.name }
        val history = historyGateway.load(refs, 0, PRELOAD_LIMIT)
        history.shouldNotBeEmpty()

        val window = requireNotNull(selectDenseWindow(history, refIndex)) {
            "레인 $DENSITY_MINIMUM_LANES 개 이상과 ref 배지가 함께 보이는 구간을 앞 ${history.size} 커밋에서 " +
                "찾지 못했습니다 — 벤치 저장소를 브랜치 수를 늘려 다시 만드세요"
        }

        val state = GraphViewState(
            loadCommitHistory = LoadCommitHistoryUseCase(RecordingHistoryGateway(window)),
            refs = refs,
            pageSize = DENSITY_WINDOW_SIZE,
        )
        // 렌더 전에 읽기를 끝낸다. 이 호출이 빠지면 "이력을 불러오는 중" 화면이 찍힌다.
        state.loadInitialPage()
        state.status shouldBe GraphLoadStatus.Loaded

        val visible = state.rows.take(DENSITY_VISIBLE_ROWS)
        val badgedRows = visible.count { refIndex.chipsFor(it.commit.id).isNotEmpty() }
        val laneCount = visible.maxOf { it.laneSpan }
        reportWindow(history.size, laneCount, badgedRows)
        visible.size shouldBe DENSITY_VISIBLE_ROWS
        laneCount shouldBeGreaterThanOrEqual DENSITY_MINIMUM_LANES
        badgedRows shouldBeGreaterThan 0

        val file = ScreenshotRenderer.render(RENDER_NAME, width = RENDER_WIDTH, height = RENDER_HEIGHT) {
            CommitGraphView(
                state = state,
                now = FIXED_NOW,
                modifier = Modifier.fillMaxSize(),
                refIndex = refIndex,
            )
        }

        file.length() shouldBeLongerThan 0
    }
})

/** 현재 브랜치. detached HEAD 면 null 이고 HEAD 칩은 그려지지 않는다. */
private fun currentBranchOf(branches: List<Branch>) = branches.firstOrNull { it.isCurrent }?.name

/** 무엇을 담은 그림인지 남긴다 — 합격선이 아니라 사람이 파일을 열 때 볼 안내다. */
private fun reportWindow(historySize: Int, laneCount: Int, badgedRows: Int) {
    println(
        "[UND-89] $RENDER_NAME.png — 앞 $historySize 커밋에서 고른 $DENSITY_WINDOW_SIZE 커밋 구간, " +
            "보이는 $DENSITY_VISIBLE_ROWS 행에 레인 $laneCount 개 · ref 배지 $badgedRows 행",
    )
}
