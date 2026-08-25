package dev.undine.presentation.tabs

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.TabAvailability
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath

@Immutable
data class RepositoryTabState(
    val path: RepositoryPath,
    val availability: TabAvailability,
    val selectedCommit: CommitId? = null,
    val scrollOffset: Int = 0,
    val filter: String = "",
    val remoteOperationRunning: Boolean = false,
)

sealed interface TabCloseRequest {
    data class Ready(val path: RepositoryPath) : TabCloseRequest
    data class ConfirmationRequired(val path: RepositoryPath) : TabCloseRequest
}

enum class TabKeyboardAction {
    Next,
    Previous,
    Close,
}

sealed interface TabKeyboardResult {
    data class Activated(val path: RepositoryPath) : TabKeyboardResult
    data class CloseRequested(val request: TabCloseRequest) : TabKeyboardResult
    data object Ignored : TabKeyboardResult
}

/**
 * 탭별 화면 상태를 보존하는 presentation 상태 홀더.
 *
 * UseCase가 내려준 [RepositorySessionSnapshot]만 소비하며 Gateway나 JGit 자원을 직접 알지 않는다.
 */
@Stable
class RepositoryTabsState(snapshot: RepositorySessionSnapshot) {
    private var tabsState by mutableStateOf(snapshot.tabs.map { it.toTabState() })
    private var activePathState by mutableStateOf(snapshot.activePath)

    val tabs: List<RepositoryTabState> get() = tabsState
    val activePath: RepositoryPath? get() = activePathState
    val activeTab: RepositoryTabState
        get() = requireNotNull(tabsState.firstOrNull { it.path == activePathState }) { "활성 탭이 없습니다" }

    val showTabBar: Boolean get() = tabsState.size > 1

    /** UseCase 실행 결과를 반영하되, 동일 경로 탭의 화면 상태는 유지한다. */
    fun apply(snapshot: RepositorySessionSnapshot) {
        val previous = tabsState.associateBy(RepositoryTabState::path)
        tabsState = snapshot.tabs.map { session ->
            previous[session.path]?.copy(availability = session.availability) ?: session.toTabState()
        }
        activePathState = snapshot.activePath
    }

    /** UI 입력이 만든 활성 탭 선택. 실제 자원 활성화는 배선이 UseCase에 요청한 뒤 [apply] 한다. */
    fun activate(path: RepositoryPath) {
        require(tabsState.any { it.path == path }) { "열려 있지 않은 탭입니다: $path" }
        activePathState = path
    }

    fun updateActiveContent(commit: CommitId?, scrollOffset: Int, filter: String) {
        val active = activePathState ?: return
        tabsState = tabsState.map { tab ->
            if (tab.path == active) {
                tab.copy(selectedCommit = commit, scrollOffset = scrollOffset, filter = filter)
            } else {
                tab
            }
        }
    }

    fun setRemoteOperationRunning(path: RepositoryPath, running: Boolean) {
        tabsState = tabsState.map { tab ->
            if (tab.path == path) tab.copy(remoteOperationRunning = running) else tab
        }
    }

    fun requestClose(path: RepositoryPath): TabCloseRequest =
        tabsState.firstOrNull { it.path == path }?.let { tab ->
            if (tab.remoteOperationRunning) TabCloseRequest.ConfirmationRequired(path) else TabCloseRequest.Ready(path)
        } ?: TabCloseRequest.Ready(path)

    fun handleKeyboard(action: TabKeyboardAction): TabKeyboardResult = when (action) {
        TabKeyboardAction.Close -> activePathState?.let { TabKeyboardResult.CloseRequested(requestClose(it)) }
            ?: TabKeyboardResult.Ignored
        TabKeyboardAction.Next -> moveActiveBy(1)
        TabKeyboardAction.Previous -> moveActiveBy(-1)
    }

    private fun moveActiveBy(delta: Int): TabKeyboardResult {
        val current = activePathState
        return if (current == null || tabsState.size < 2) {
            TabKeyboardResult.Ignored
        } else {
            val index = tabsState.indexOfFirst { it.path == current }
            val next = tabsState[(index + delta).mod(tabsState.size)].path
            activePathState = next
            TabKeyboardResult.Activated(next)
        }
    }
}

private fun dev.undine.application.session.TabSession.toTabState(): RepositoryTabState = RepositoryTabState(
    path = path,
    availability = availability,
)
