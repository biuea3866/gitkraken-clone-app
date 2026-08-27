package dev.undine.presentation.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.preferences
import dev.undine.presentation.i18n.strings

private const val PREVIOUS_TAB = -1
private const val NEXT_TAB = 1

/**
 * 환경설정 화면의 셸 — 탭 막대, 선택 탭 내용, 전체 초기화.
 *
 * **저장 버튼이 없다.** 값 편집기는 각 탭이 붙이고, 바뀐 값은 [PreferencesState.apply] 로 곧바로
 * 반영·저장된다. 탭 내용은 후속 티켓이 채우는 스텁이며 이 셸은 파일 경계와 공통 계약만 정한다.
 */
@Composable
fun PreferencesScreen(
    state: PreferencesState,
    modifier: Modifier = Modifier,
) {
    val texts = strings.preferences
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(spacing.large)
            .testTag(PreferencesTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        BasicText(
            text = texts.title,
            style = UndineTokens.typography.title.copy(color = colors.foregroundPrimary),
        )
        PreferencesTabBar(state)
        state.loadFailure?.let { PreferencesNotice(texts.loadFailed, PreferencesTags.LOAD_FAILURE) }
        state.saveFailure?.let { PreferencesNotice(texts.saveFailed, PreferencesTags.SAVE_FAILURE) }

        // 선택된 탭 하나만 그린다 — 나머지 탭의 스텁은 컴포지션에 들어가지 않는다.
        Column(modifier = Modifier.testTag(PreferencesTags.CONTENT)) {
            PreferencesTab.entries.filter(state::rendersContent).forEach { tab ->
                PreferencesTabContent(tab)
            }
        }
        PreferencesResetAll(state)
    }
}

/** 탭 막대. 클릭과 좌우 방향키가 같은 전환 경로로 들어간다. */
@Composable
private fun PreferencesTabBar(state: PreferencesState) {
    val texts = strings.preferences
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Row(
        modifier = Modifier
            .onPreviewKeyEvent { event -> handleTabKey(event, state) }
            .focusable()
            .testTag(PreferencesTags.TAB_BAR),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        PreferencesTab.entries.forEach { tab ->
            val selected = state.rendersContent(tab)
            BasicText(
                text = tab.titleIn(texts),
                style = UndineTokens.typography.body.copy(
                    color = if (selected) colors.accent else colors.foregroundSecondary,
                ),
                modifier = Modifier
                    .clickable(role = Role.Tab) { state.selectTab(tab) }
                    .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                    .testTag(PreferencesTags.TAB),
            )
        }
    }
}

/** 선택 탭의 내용. 각 탭은 후속 티켓이 채운다. */
@Composable
private fun PreferencesTabContent(tab: PreferencesTab) {
    when (tab) {
        PreferencesTab.GENERAL -> GeneralPreferencesContent()
        PreferencesTab.GIT -> GitPreferencesContent()
        PreferencesTab.ACCOUNTS -> AccountPreferencesContent()
        PreferencesTab.TOOLS -> ToolPreferencesContent()
        PreferencesTab.SHORTCUTS -> ShortcutPreferencesContent()
        PreferencesTab.ADVANCED -> AdvancedPreferencesContent()
    }
}

/**
 * 전체 초기화. 되돌릴 수 없으므로 **무엇이 지워지는지 알리고 확인을 받은 뒤에만** 수행한다.
 */
@Composable
private fun PreferencesResetAll(state: PreferencesState) {
    val texts = strings.preferences
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        UndineToolbarButton(
            label = texts.resetAll,
            onClick = state::requestResetAll,
            modifier = Modifier.testTag(PreferencesTags.RESET_ALL),
        )
        if (state.isResetConfirmationVisible) {
            Column(
                modifier = Modifier.testTag(PreferencesTags.RESET_CONFIRMATION),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                BasicText(
                    text = texts.resetAllWarning,
                    style = UndineTokens.typography.caption.copy(color = colors.warning),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    UndineToolbarButton(label = texts.resetAllConfirm, onClick = state::confirmResetAll)
                    UndineToolbarButton(label = texts.resetAllCancel, onClick = state::cancelResetAll)
                }
            }
        }
    }
}

@Composable
private fun PreferencesNotice(message: String, tag: String) {
    BasicText(
        text = message,
        style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
        modifier = Modifier.testTag(tag),
    )
}

/** 좌우 방향키로 탭을 옮긴다 — 주요 동작에 마우스 전용 경로를 두지 않는다. */
private fun handleTabKey(event: KeyEvent, state: PreferencesState): Boolean {
    val shift = tabShiftFor(event) ?: return false
    state.selectTab(state.selectedTab.shifted(shift))
    return true
}

private fun tabShiftFor(event: KeyEvent): Int? = when {
    event.type != KeyEventType.KeyDown -> null
    event.key == Key.DirectionLeft -> PREVIOUS_TAB
    event.key == Key.DirectionRight -> NEXT_TAB
    else -> null
}
