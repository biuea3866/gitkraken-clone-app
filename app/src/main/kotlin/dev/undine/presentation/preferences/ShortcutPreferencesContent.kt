package dev.undine.presentation.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.shortcutOf

/**
 * 단축키 탭 — 명령별 실효 단축키 표시, 재지정, 충돌 해소, 항목별 기본값 복원.
 *
 * 판단은 전부 [ShortcutPreferencesController] 가 하고 여기서는 그리기만 한다. 홀더를 `remember`
 * 하는 이유는 시그니처를 늘릴 수 없어서다 — 탭이 인자를 추가하면 수정 대상이 아닌
 * `PreferencesScreen` 까지 바뀐다.
 *
 * **저장 결과로만 갱신한다.** 저장된 설정이 바뀔 때만 [ShortcutPreferencesController.synchronize] 가
 * 돌아 행과 실효 단축키를 다시 만든다. 저장에 실패하면 설정이 그대로라 화면도 저장된 값에 머물고,
 * 사유는 셸의 실패 알림이 보여 준다.
 *
 * **"단축키 없음" 을 제공하지 않는다** (결정 G19). 되돌리는 길은 항목별 기본값 복원 하나다.
 */
@Composable
fun ShortcutPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    commands: CommandRegistry,
    modifier: Modifier = Modifier,
) {
    val controller = remember(state, commands) { ShortcutPreferencesController(state, commands) }
    LaunchedEffect(controller, state.settings) { controller.synchronize() }

    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier.testTag(ShortcutPreferencesTags.LIST),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        UnappliedNotice(controller.unappliedCommandIds, texts)
        ShortcutListHeader(texts)
        controller.rows.forEach { row ->
            ShortcutRowItem(row, controller, commands, texts)
            controller.conflict
                ?.takeIf { it.commandId == row.commandId }
                ?.let { ConflictNotice(it, controller, texts) }
        }
    }
}

/**
 * 목록 머리글. 등록된 명령이 하나도 없어도(배선 전) 탭이 무엇을 다루는지 남는다 —
 * 빈 화면은 "준비 중" 과 구분되지 않는다.
 */
@Composable
private fun ShortcutListHeader(texts: PreferencesStrings) {
    val style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary)

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.medium)) {
        BasicText(text = texts.shortcutCommand, style = style)
        BasicText(text = texts.shortcutBinding, style = style)
    }
}

/**
 * 묶지 못한 커맨드 id 요약. 사용자가 지금 할 일이 없는 정보라 대화상자를 띄우지 않고 한 줄로 알린다
 * (결정 G20). 개별 항목은 등록 여부와 무관하게 자기 행에서 미적용으로 표시된다.
 */
@Composable
private fun UnappliedNotice(unapplied: List<CommandId>, texts: PreferencesStrings) {
    if (unapplied.isEmpty()) return

    BasicText(
        text = "${texts.shortcutApplyFailed}: ${unapplied.joinToString { it.value }}",
        style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
        modifier = Modifier.testTag(ShortcutPreferencesTags.UNAPPLIED),
    )
}

/**
 * 명령 한 줄. 공통 행 계약을 그대로 쓰되 복원 버튼은 직접 붙인다 — 공통 버튼은
 * `SettingsPreference` 단위라 단축키 매핑 **전체**를 비우고, 이 탭이 필요한 것은 커맨드 id 하나다.
 *
 * 등록되지 않은 id 의 행은 미적용 사실만 남기고 동작 칸을 비운다 — 잡을 명령이 없어 재지정할 수
 * 없고, 저장값을 지우면 그 명령이 배선될 때 사용자가 지정했던 키가 이미 사라진 뒤다.
 */
@Composable
private fun ShortcutRowItem(
    row: ShortcutPreferencesRow,
    controller: ShortcutPreferencesController,
    commands: CommandRegistry,
    texts: PreferencesStrings,
) {
    PreferencesRowItem(
        row = PreferencesRow(
            label = row.title,
            value = row.valueIn(texts),
            source = PreferenceValueSource.APP_SETTINGS,
            sourceLabel = row.sourceLabelIn(texts),
            restorablePreference = null,
        ),
        onRestoreDefault = {},
        modifier = Modifier.testTag(ShortcutPreferencesTags.ROW),
    ) {
        if (!row.isRegistered) {
            return@PreferencesRowItem
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            if (controller.capturingCommandId == row.commandId) {
                ShortcutCaptureField(texts) { event ->
                    shortcutOf(event, commands.platform)?.let(controller::capture) ?: false
                }
            } else {
                UndineToolbarButton(
                    label = texts.shortcutBinding,
                    onClick = { controller.startCapture(row.commandId) },
                    modifier = Modifier.testTag(ShortcutPreferencesTags.REBIND),
                )
            }
            if (row.isOverridden) {
                UndineToolbarButton(
                    label = texts.restoreDefault,
                    onClick = { controller.restoreDefault(row.commandId) },
                    modifier = Modifier.testTag(ShortcutPreferencesTags.RESTORE_DEFAULT),
                )
            }
        }
    }
}

/**
 * 새 조합을 받는 자리. 열리면 곧바로 포커스를 가져가 마우스 없이도 이어서 누를 수 있다.
 * `Escape` 로 닫히고, 수식키만 눌린 동안에는 입력을 소비하지 않는다.
 */
@Composable
private fun ShortcutCaptureField(texts: PreferencesStrings, onKey: (KeyEvent) -> Boolean) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    BasicText(
        text = texts.shortcutBinding,
        style = UndineTokens.typography.body.copy(color = UndineTokens.color.accent),
        modifier = Modifier
            .onPreviewKeyEvent(onKey)
            .focusRequester(focusRequester)
            .focusable()
            .background(UndineTokens.color.surface)
            .padding(horizontal = UndineTokens.spacing.medium, vertical = UndineTokens.spacing.small)
            .testTag(ShortcutPreferencesTags.CAPTURE),
    )
}

/** 겹치는 명령을 보여주고 교체 여부를 묻는다. 확인 전에는 저장값도 실효 단축키도 그대로다. */
@Composable
private fun ConflictNotice(
    conflict: ShortcutConflict,
    controller: ShortcutPreferencesController,
    texts: PreferencesStrings,
) {
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.testTag(ShortcutPreferencesTags.CONFLICT),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        BasicText(
            text = "${texts.shortcutConflict}: ${conflict.ownerTitle}",
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = texts.shortcutReplaceConfirm,
                onClick = controller::confirmReplace,
                modifier = Modifier.testTag(ShortcutPreferencesTags.CONFLICT_CONFIRM),
            )
            UndineToolbarButton(
                label = texts.resetAllCancel,
                onClick = controller::cancelReplace,
                modifier = Modifier.testTag(ShortcutPreferencesTags.CONFLICT_CANCEL),
            )
        }
    }
}
