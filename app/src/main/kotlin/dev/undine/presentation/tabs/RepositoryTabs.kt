package dev.undine.presentation.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabId
import dev.undine.domain.RepositoryPath
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.tabs

/**
 * 여러 저장소 사이를 전환하는 탭 막대.
 *
 * 탭이 하나면 화면에서 숨긴다. Ctrl+Tab / Ctrl+Shift+Tab / Ctrl+W는 마우스와 동등한 전환·닫기
 * 경로이며, 실제 UseCase 호출은 상위 배선이 콜백으로 수행한 후 상태 스냅샷을 다시 [RepositoryTabsState.apply]
 * 에 전달한다.
 */
@Composable
fun RepositoryTabs(
    state: RepositoryTabsState,
    onActivate: (TabId) -> Unit,
    onCloseRequested: (TabCloseRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.showTabBar) return

    val tabStrings = strings.tabs
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    Row(
        modifier = modifier
            .background(colors.surface)
            .onPreviewKeyEvent { event ->
                state.handleKeyEvent(event, onActivate, onCloseRequested)
            }
            .focusable(),
    ) {
        state.tabs.forEach { tab ->
            val selected = tab.id == state.activeTabId
            Column(
                modifier = Modifier
                    .background(if (selected) colors.background else colors.surface)
                    .clickable(role = Role.Tab) {
                        state.activate(tab.id)
                        onActivate(tab.id)
                    }
                    .padding(horizontal = spacing.medium, vertical = spacing.small),
            ) {
                Text(
                    text = tab.path.displayName(),
                    color = if (selected) colors.foregroundPrimary else colors.foregroundSecondary,
                )
                if (tab.availability == TabAvailability.MissingPath) {
                    Text(text = tabStrings.missingPath, color = colors.warning)
                }
                Text(
                    text = tabStrings.closeTab,
                    color = colors.foregroundSecondary,
                    modifier = Modifier.clickable { onCloseRequested(state.requestClose(tab.id)) },
                )
            }
        }
    }
}

private fun RepositoryTabsState.handleKeyEvent(
    event: KeyEvent,
    onActivate: (TabId) -> Unit,
    onCloseRequested: (TabCloseRequest) -> Unit,
): Boolean {
    val action = if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
        keyboardActionFor(event)
    } else {
        null
    }
    return action?.let { requestedAction ->
        when (val result = handleKeyboard(requestedAction)) {
            is TabKeyboardResult.Activated -> onActivate(result.tabId)
            is TabKeyboardResult.CloseRequested -> onCloseRequested(result.request)
            TabKeyboardResult.Ignored -> Unit
        }
        true
    } ?: false
}

private fun keyboardActionFor(event: KeyEvent): TabKeyboardAction? = when {
    event.key == Key.Tab && event.isShiftPressed -> TabKeyboardAction.Previous
    event.key == Key.Tab -> TabKeyboardAction.Next
    event.key == Key.W -> TabKeyboardAction.Close
    else -> null
}

private fun RepositoryPath.displayName(): String = value.substringAfterLast('/').ifBlank { value }
