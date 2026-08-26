package dev.undine.presentation.tabs

import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabId
import dev.undine.application.session.TabSession
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")
private val GAMMA = RepositoryPath("/repositories/gamma")
private val COMMIT = CommitId.of("0".repeat(40))

private fun tabIdAt(index: Int) = TabId(index.toLong() + 1)

class RepositoryTabsStateSpec : FunSpec({

    test("탭마다 선택 커밋·스크롤·필터를 독립적으로 보존한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 1))

        state.updateActiveContent(commit = COMMIT, scrollOffset = 48, filter = "feature")
        state.activate(tabIdAt(0))
        state.updateActiveContent(commit = null, scrollOffset = 12, filter = "bugfix")
        state.activate(tabIdAt(1))

        state.activeTab shouldBe RepositoryTabState(
            id = tabIdAt(1),
            path = BETA,
            availability = TabAvailability.Available,
            selectedCommit = COMMIT,
            scrollOffset = 48,
            filter = "feature",
        )
    }

    test("같은 저장소를 가리키는 두 탭은 화면 상태를 공유하지 않는다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, ALPHA), activeIndex = 0))

        state.updateActiveContent(commit = COMMIT, scrollOffset = 30, filter = "first")
        state.activate(tabIdAt(1))
        state.updateActiveContent(commit = null, scrollOffset = 90, filter = "second")

        state.tabs[0].filter shouldBe "first"
        state.tabs[0].scrollOffset shouldBe 30
        state.tabs[0].selectedCommit shouldBe COMMIT
        state.tabs[1].filter shouldBe "second"
        state.tabs[1].scrollOffset shouldBe 90
        state.tabs[1].selectedCommit shouldBe null
    }

    test("같은 저장소를 가리키는 두 탭 중 하나만 원격 작업 확인을 요구한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, ALPHA), activeIndex = 0))
        state.setRemoteOperationRunning(tabIdAt(1), running = true)

        state.requestClose(tabIdAt(0)) shouldBe TabCloseRequest.Ready(tabIdAt(0))
        state.requestClose(tabIdAt(1)) shouldBe TabCloseRequest.ConfirmationRequired(tabIdAt(1))
    }

    test("탭이 하나면 막대를 숨기지만 탭 상태는 유지한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA), activeIndex = 0))

        state.showTabBar shouldBe false
        state.activeTab.path shouldBe ALPHA
    }

    test("원격 작업 중인 탭을 닫으면 확인을 요청한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA), activeIndex = 0))
        state.setRemoteOperationRunning(tabIdAt(0), running = true)

        state.requestClose(tabIdAt(0)) shouldBe TabCloseRequest.ConfirmationRequired(tabIdAt(0))
    }

    test("스냅샷을 적용해도 남아 있는 탭의 화면 상태는 보존한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 0))
        state.updateActiveContent(commit = COMMIT, scrollOffset = 24, filter = "release")

        state.apply(
            RepositorySessionSnapshot(
                tabs = listOf(
                    TabSession(tabIdAt(0), ALPHA, TabAvailability.MissingPath, resourcesLoaded = false),
                    TabSession(TabId(9), GAMMA, TabAvailability.Available, resourcesLoaded = true),
                ),
                activeTabId = TabId(9),
            ),
        )

        state.tabs.map { it.path } shouldBe listOf(ALPHA, GAMMA)
        state.tabs.first() shouldBe RepositoryTabState(
            id = tabIdAt(0),
            path = ALPHA,
            availability = TabAvailability.MissingPath,
            selectedCommit = COMMIT,
            scrollOffset = 24,
            filter = "release",
        )
        state.activeTab.path shouldBe GAMMA
        state.activeTab.selectedCommit shouldBe null
    }

    test("스냅샷이 되찾은 경로는 다시 Available 로 갱신한다") {
        val state = RepositoryTabsState(
            RepositorySessionSnapshot(
                tabs = listOf(TabSession(tabIdAt(0), ALPHA, TabAvailability.MissingPath, resourcesLoaded = false)),
                activeTabId = tabIdAt(0),
            ),
        )

        state.apply(snapshotOf(listOf(ALPHA), activeIndex = 0))

        state.activeTab.availability shouldBe TabAvailability.Available
    }

    test("키보드 다음 탭 동작은 순환해 활성 탭을 바꾼다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 1))

        state.handleKeyboard(TabKeyboardAction.Next) shouldBe TabKeyboardResult.Activated(tabIdAt(0))
        state.activeTab.path shouldBe ALPHA
    }

    test("키보드 이전 탭 동작은 첫 탭에서 마지막 탭으로 되감는다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA, GAMMA), activeIndex = 0))

        state.handleKeyboard(TabKeyboardAction.Previous) shouldBe TabKeyboardResult.Activated(tabIdAt(2))
        state.activeTab.path shouldBe GAMMA
    }

    test("키보드 이전 탭 동작은 탭이 하나뿐이면 무시한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA), activeIndex = 0))

        state.handleKeyboard(TabKeyboardAction.Previous) shouldBe TabKeyboardResult.Ignored
        state.activeTab.path shouldBe ALPHA
    }

    test("키보드 닫기는 원격 작업이 없으면 바로 닫기 요청을 낸다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 1))

        state.handleKeyboard(TabKeyboardAction.Close) shouldBe
            TabKeyboardResult.CloseRequested(TabCloseRequest.Ready(tabIdAt(1)))
    }

    test("키보드 닫기도 원격 작업 중이면 확인을 요청한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), activeIndex = 1))
        state.setRemoteOperationRunning(tabIdAt(1), running = true)

        state.handleKeyboard(TabKeyboardAction.Close) shouldBe
            TabKeyboardResult.CloseRequested(TabCloseRequest.ConfirmationRequired(tabIdAt(1)))
    }
})

private fun snapshotOf(paths: List<RepositoryPath>, activeIndex: Int): RepositorySessionSnapshot =
    RepositorySessionSnapshot(
        tabs = paths.mapIndexed { index, path ->
            TabSession(tabIdAt(index), path, TabAvailability.Available, resourcesLoaded = index == activeIndex)
        },
        activeTabId = tabIdAt(activeIndex),
    )
