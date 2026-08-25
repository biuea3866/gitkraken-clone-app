package dev.undine.presentation.tabs

import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabSession
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")
private val COMMIT = CommitId.of("0".repeat(40))

class RepositoryTabsStateSpec : FunSpec({

    test("탭마다 선택 커밋·스크롤·필터를 독립적으로 보존한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), active = BETA))

        state.updateActiveContent(commit = COMMIT, scrollOffset = 48, filter = "feature")
        state.activate(ALPHA)
        state.updateActiveContent(commit = null, scrollOffset = 12, filter = "bugfix")
        state.activate(BETA)

        state.activeTab shouldBe RepositoryTabState(
            path = BETA,
            availability = TabAvailability.Available,
            selectedCommit = COMMIT,
            scrollOffset = 48,
            filter = "feature",
        )
    }

    test("탭이 하나면 막대를 숨기지만 탭 상태는 유지한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA), active = ALPHA))

        state.showTabBar shouldBe false
        state.activeTab.path shouldBe ALPHA
    }

    test("원격 작업 중인 탭을 닫으면 확인을 요청한다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA), active = ALPHA))
        state.setRemoteOperationRunning(ALPHA, running = true)

        state.requestClose(ALPHA) shouldBe TabCloseRequest.ConfirmationRequired(ALPHA)
    }

    test("키보드 다음 탭 동작은 순환해 활성 탭을 바꾼다") {
        val state = RepositoryTabsState(snapshotOf(listOf(ALPHA, BETA), active = BETA))

        state.handleKeyboard(TabKeyboardAction.Next) shouldBe TabKeyboardResult.Activated(ALPHA)
        state.activeTab.path shouldBe ALPHA
    }
})

private fun snapshotOf(paths: List<RepositoryPath>, active: RepositoryPath): RepositorySessionSnapshot =
    RepositorySessionSnapshot(
        tabs = paths.map { TabSession(it, TabAvailability.Available, resourcesLoaded = it == active) },
        activePath = active,
    )
