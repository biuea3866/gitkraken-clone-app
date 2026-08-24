package dev.undine.presentation.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.undine.domain.Branch
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.presentation.graph.CommitGraphView
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.graph.GraphViewState
import dev.undine.presentation.shell.AppShell
import dev.undine.presentation.shell.AppShellSlots
import dev.undine.presentation.shell.rememberAppShellState
import dev.undine.testsupport.FIXED_NOW
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import dev.undine.presentation.staging.FakeRepositoryGateway
import dev.undine.presentation.staging.RecordingStagingGateway
import dev.undine.presentation.staging.statusOf
import dev.undine.presentation.staging.stagingStateWith
import dev.undine.application.graph.LoadCommitHistoryUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan

private val REFS = listOf(RefName("refs/heads/main"))

/**
 * 화면을 PNG 로 렌더해 **사람이 눈으로 확인할 수 있게** 남긴다 (`build/screenshots/`).
 *
 * 단정하는 것은 "렌더가 끝나고 파일이 비어 있지 않다" 뿐이다 — 기준 이미지와 픽셀 비교하지 않는다.
 * 그 비교는 폰트·렌더러 버전에 따라 깨져 유지 비용이 크고, 여기 목적은 회귀 차단이 아니라
 * **시각 결정(색·행 밀도)을 눈으로 판단할 재료를 만드는 것**이다.
 */
class ScreenshotRenderSpec : FunSpec({

    test("셸 3분할이 다크 테마로 렌더된다") {
        val file = ScreenshotRenderer.render("shell-dark") {
            AppShell(
                state = rememberAppShellState(),
                slots = AppShellSlots(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        file.length() shouldBeGreaterThan 0
    }

    test("커밋 그래프가 다크 테마로 렌더된다") {
        val file = ScreenshotRenderer.render("graph-dark", width = 900, height = 560) {
            val state = remember {
                GraphViewState(
                    loadCommitHistory = LoadCommitHistoryUseCase(RecordingHistoryGateway(sampleHistory())),
                    refs = REFS,
                )
            }
            val refIndex = remember { sampleRefIndex() }
            LaunchedGraph(state = state, refIndex = refIndex)
        }

        file.length() shouldBeGreaterThan 0
    }

    test("스테이징 패널이 다크 테마로 렌더된다") {
        val file = ScreenshotRenderer.render("staging-dark", width = 900, height = 620) {
            val state = remember {
                stagingStateWith(
                    FakeRepositoryGateway(
                        statusOf(
                            staged = listOf("app/src/main/kotlin/dev/undine/presentation/staging/StagingPanel.kt"),
                            unstaged = listOf("tickets/README.md", "app/build.gradle.kts"),
                            untracked = listOf("docs/new-note.md"),
                        ),
                    ),
                    RecordingStagingGateway(),
                )
            }
            LaunchedStaging(state)
        }

        file.length() shouldBeGreaterThan 0
    }

})

/** 첫 페이지를 실어야 행이 그려진다 — 렌더 전에 로드를 끝낸다. */
@androidx.compose.runtime.Composable
private fun LaunchedGraph(state: GraphViewState, refIndex: CommitRefIndex) {
    androidx.compose.runtime.LaunchedEffect(Unit) { state.loadInitialPage() }
    CommitGraphView(
        state = state,
        now = FIXED_NOW,
        modifier = Modifier.fillMaxSize(),
        refIndex = refIndex,
    )
}

/** 병합·분기가 섞인 짧은 이력. 레인이 둘 이상 나와야 색 배치를 볼 수 있다. */
private fun sampleHistory() = listOf(
    commit(1, 2, message = "그래프 레인 배치를 다듬는다"),
    commit(2, 3, 4, message = "Merge pull request #41"),
    commit(3, 5, message = "diff 뷰어 분할 보기를 추가한다"),
    commit(4, 5, message = "사이드바 원격 행 삭제를 막는다"),
    commit(5, 6, message = "커밋 상세 패널"),
    commit(6, message = "최초 커밋"),
)

private fun sampleRefIndex(): CommitRefIndex = CommitRefIndex.of(
    branches = listOf(
        Branch(
            name = RefName("main"),
            target = commitId(1),
            isCurrent = true,
            isRemote = false,
            upstream = RefName("origin/main"),
            ahead = 2,
            behind = 0,
        ),
        Branch(
            name = RefName("origin/main"),
            target = commitId(3),
            isCurrent = false,
            isRemote = true,
            upstream = null,
            ahead = 0,
            behind = 0,
        ),
    ),
    tags = listOf(
        Tag(name = RefName("v0.1.0"), target = commitId(5), isAnnotated = false, message = null, tagger = null),
    ),
    currentBranch = RefName("main"),
)

/** 목록을 실은 뒤 렌더한다 — refresh 전에는 빈 상태가 그려진다. */
@androidx.compose.runtime.Composable
private fun LaunchedStaging(state: dev.undine.presentation.staging.StagingState) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        state.refresh()
        state.changeMessage("스테이징 패널을 붙인다")
    }
    dev.undine.presentation.staging.StagingPanel(state = state, modifier = Modifier.fillMaxSize())
}
