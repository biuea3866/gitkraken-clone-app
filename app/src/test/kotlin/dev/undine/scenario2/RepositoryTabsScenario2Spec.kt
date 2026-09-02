package dev.undine.scenario2

import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.presentation.tabs.RepositoryTabsState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.api.Git
import java.io.File

private const val HISTORY_LIMIT = 10
private const val FIRST_SCROLL = 3
private const val SECOND_SCROLL = 7

/**
 * 2차 시나리오 9 — 저장소 둘을 탭으로 열어 전환하고, 탭별 상태 독립과 닫을 때의 자원 해제를 확인한다.
 *
 * 화면 상태는 `RepositoryTabsState` 가, 세션 핸들은 `RepositorySessionUseCase` 가 소유한다 — 앱 배선과
 * 같은 조합으로 잇는다. 자원 해제는 **관찰 가능한 결과**로 본다: 닫은 뒤의 저장소 조회는 빈 결과가
 * 아니라 실패여야 한다. JGit 이 핸들 수를 외부에 노출하지 않으므로 "핸들 0" 을 세지 않는다.
 */
class RepositoryTabsScenario2Spec : FunSpec({

    test("두 저장소 탭이 각자의 상태를 유지하고 닫을 때 세션 자원이 해제된다") {
        val root = tempdir()
        val first = seedRepository(File(root, "first"))
        val second = seedRepository(File(root, "second"))
        appendCommit(first, "첫 저장소 커밋")
        appendCommit(second, "둘째 저장소 커밋")

        scenario2AppAt(first).use { app ->
            val tabs = RepositoryTabsState(RepositorySessionSnapshot(tabs = emptyList(), activeTabId = null))

            // 첫 저장소를 탭으로 연다.
            app.repositorySession.open(RepositoryPath(first.absolutePath)) { tabs.apply(it) }
            val firstTabId = tabs.activeTab.id
            tabs.activeTab.path shouldBe RepositoryPath(first.absolutePath)
            tabs.activeTab.availability shouldBe TabAvailability.Available
            app.historyMessages() shouldContainExactly listOf("첫 저장소 커밋", "initial")
            tabs.updateActiveContent(commit = null, scrollOffset = FIRST_SCROLL, filter = "첫")

            // 둘째 저장소를 **새 탭**으로 연다 — 경로가 달라도 같아도 언제나 새 탭이다.
            val bothTabs = app.repositorySession.open(RepositoryPath(second.absolutePath)) { tabs.apply(it) }
            val secondTabId = tabs.activeTab.id
            secondTabId shouldNotBe firstTabId
            app.historyMessages() shouldContainExactly listOf("둘째 저장소 커밋", "initial")
            tabs.updateActiveContent(commit = null, scrollOffset = SECOND_SCROLL, filter = "둘")

            // 두 탭은 서로 다른 세션을 쓴다 — 하나의 핸들을 공유하면 한 탭을 닫을 때 다른 탭이 죽는다.
            val sessionKeys = bothTabs.tabs.mapNotNull { it.sessionKey }
            sessionKeys.distinct().size shouldBe 2

            // 첫 탭으로 되돌아가면 조회 대상도 함께 돌아온다.
            app.repositorySession.activate(firstTabId) { tabs.apply(it) }
            app.historyMessages() shouldContainExactly listOf("첫 저장소 커밋", "initial")

            // 탭별 화면 상태는 전환에도 섞이지 않는다.
            tabs.tabs.single { it.id == firstTabId }.filter shouldBe "첫"
            tabs.tabs.single { it.id == firstTabId }.scrollOffset shouldBe FIRST_SCROLL
            tabs.tabs.single { it.id == secondTabId }.filter shouldBe "둘"
            tabs.tabs.single { it.id == secondTabId }.scrollOffset shouldBe SECOND_SCROLL

            // 둘째 탭을 닫으면 그 탭만 사라지고 남은 탭은 그대로 동작한다.
            val afterClose = app.repositorySession.close(secondTabId) { tabs.apply(it) }
            afterClose.tabs.map { it.id } shouldContainExactly listOf(firstTabId)
            tabs.activeTab.id shouldBe firstTabId
            app.historyMessages() shouldContainExactly listOf("첫 저장소 커밋", "initial")

            // 마지막 탭까지 닫으면 세션 자원이 해제된다 — 이후 조회는 빈 결과가 아니라 실패다.
            val afterLast = app.repositorySession.close(firstTabId) { tabs.apply(it) }
            afterLast.tabs.shouldBeEmpty()
            afterLast.activeTabId shouldBe null
            tabs.tabs.shouldBeEmpty()

            val failure = shouldThrow<UndineException.StateViolation> {
                app.readActiveRepository { repository -> repository.repositoryState }
            }
            failure.message.orEmpty() shouldContain "열려 있지"
        }
    }
})

private suspend fun Scenario2App.historyMessages(): List<String> =
    loadHistory.execute(listOf(mainRef()), offset = 0, limit = HISTORY_LIMIT).map { it.message.trim() }

/** 저장소마다 다른 이력을 만들어 둔다 — 탭이 정말 다른 저장소를 보는지 이 차이로 확인한다. */
private fun appendCommit(work: File, message: String) {
    Git.open(work).use { git ->
        git.commitFile(work, "${message}.txt", "$message\n", message)
    }
}
