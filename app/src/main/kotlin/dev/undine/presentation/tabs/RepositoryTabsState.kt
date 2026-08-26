package dev.undine.presentation.tabs

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabId
import dev.undine.application.session.TabSession
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath

@Immutable
data class RepositoryTabState(
    val id: TabId,
    val path: RepositoryPath,
    val availability: TabAvailability,
    val selectedCommit: CommitId? = null,
    val scrollOffset: Int = 0,
    val filter: String = "",
    val remoteOperationRunning: Boolean = false,
)

sealed interface TabCloseRequest {
    data class Ready(val tabId: TabId) : TabCloseRequest
    data class ConfirmationRequired(val tabId: TabId) : TabCloseRequest
}

enum class TabKeyboardAction {
    Next,
    Previous,
    Close,
}

sealed interface TabKeyboardResult {
    data class Activated(val tabId: TabId) : TabKeyboardResult
    data class CloseRequested(val request: TabCloseRequest) : TabKeyboardResult
    data object Ignored : TabKeyboardResult
}

/**
 * 탭별 화면 상태를 보존하는 presentation 상태 홀더.
 *
 * UseCase가 내려준 [RepositorySessionSnapshot]만 소비하며 Gateway나 JGit 자원을 직접 알지 않는다.
 *
 * 탭은 [TabId] 로 식별한다 — 같은 저장소를 두 탭으로 열 수 있으므로 경로로 찾으면 두 탭이 서로의
 * 선택 커밋·스크롤·필터를 덮어쓴다.
 */
@Stable
class RepositoryTabsState(snapshot: RepositorySessionSnapshot) {
    private var tabsState by mutableStateOf(snapshot.tabs.map { it.toTabState() })
    private var activeTabIdState by mutableStateOf(snapshot.activeTabId)

    val tabs: List<RepositoryTabState> get() = tabsState
    val activeTabId: TabId? get() = activeTabIdState
    val activeTab: RepositoryTabState
        get() = requireNotNull(tabsState.firstOrNull { it.id == activeTabIdState }) { "활성 탭이 없습니다" }

    val showTabBar: Boolean get() = tabsState.size > 1

    /** UseCase 실행 결과를 반영하되, 남아 있는 탭의 화면 상태는 유지한다. */
    fun apply(snapshot: RepositorySessionSnapshot) {
        val previous = tabsState.associateBy(RepositoryTabState::id)
        tabsState = snapshot.tabs.map { session ->
            previous[session.id]?.copy(path = session.path, availability = session.availability)
                ?: session.toTabState()
        }
        activeTabIdState = snapshot.activeTabId
    }

    /** UI 입력이 만든 활성 탭 선택. 실제 자원 활성화는 배선이 UseCase에 요청한 뒤 [apply] 한다. */
    fun activate(tabId: TabId) {
        require(tabsState.any { it.id == tabId }) { "열려 있지 않은 탭입니다: $tabId" }
        activeTabIdState = tabId
    }

    fun updateActiveContent(commit: CommitId?, scrollOffset: Int, filter: String) {
        val active = activeTabIdState ?: return
        tabsState = tabsState.map { tab ->
            if (tab.id == active) {
                tab.copy(selectedCommit = commit, scrollOffset = scrollOffset, filter = filter)
            } else {
                tab
            }
        }
    }

    fun setRemoteOperationRunning(tabId: TabId, running: Boolean) {
        tabsState = tabsState.map { tab ->
            if (tab.id == tabId) tab.copy(remoteOperationRunning = running) else tab
        }
    }

    fun requestClose(tabId: TabId): TabCloseRequest =
        tabsState.firstOrNull { it.id == tabId }?.let { tab ->
            if (tab.remoteOperationRunning) {
                TabCloseRequest.ConfirmationRequired(tabId)
            } else {
                TabCloseRequest.Ready(tabId)
            }
        } ?: TabCloseRequest.Ready(tabId)

    fun handleKeyboard(action: TabKeyboardAction): TabKeyboardResult = when (action) {
        TabKeyboardAction.Close -> activeTabIdState?.let { TabKeyboardResult.CloseRequested(requestClose(it)) }
            ?: TabKeyboardResult.Ignored
        TabKeyboardAction.Next -> moveActiveBy(1)
        TabKeyboardAction.Previous -> moveActiveBy(-1)
    }

    private fun moveActiveBy(delta: Int): TabKeyboardResult {
        val current = activeTabIdState
        return if (current == null || tabsState.size < 2) {
            TabKeyboardResult.Ignored
        } else {
            val index = tabsState.indexOfFirst { it.id == current }
            val next = tabsState[(index + delta).mod(tabsState.size)].id
            activeTabIdState = next
            TabKeyboardResult.Activated(next)
        }
    }
}

private fun TabSession.toTabState(): RepositoryTabState = RepositoryTabState(
    id = id,
    path = path,
    availability = availability,
)
