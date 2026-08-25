package dev.undine.presentation.undo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.type
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.undo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 툴바 Undo 동작과 세션 이력 패널을 함께 그린다.
 *
 * Ctrl/Cmd+Z는 클릭과 같은 [UndoState.undo] 경로로 들어간다. 화면 등록·전역 단축키 배선은 이
 * 티켓 범위가 아니므로 이 composable을 배치하는 쪽이 포커스 루트로 쓴다.
 */
@Composable
fun UndoPanel(
    state: UndoState,
    modifier: Modifier = Modifier,
) {
    val texts = strings.undo
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val button = undoButtonPresentation(state.target, state.isUndoing, strings)
    val history = undoHistoryPresentation(state.history, strings)

    Column(
        modifier = modifier
            .background(colors.background)
            .onPreviewKeyEvent { event -> handleUndoKey(event, state) }
            .testTag(UndoTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        UndineToolbarButton(
            label = button.label,
            onClick = state::undo,
            modifier = Modifier.fillMaxWidth().testTag(UndoTags.BUTTON),
            enabled = button.enabled,
        )
        button.tooltip?.let { tooltip ->
            BasicText(
                text = tooltip,
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
        }
        button.disabledReason?.let { reason ->
            BasicText(
                text = reason,
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
        }
        undoOutcomeMessage(state.lastOutcome, strings)?.let { message ->
            BasicText(
                text = message,
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
        }
        BasicText(
            text = texts.historyTitle,
            style = UndineTokens.typography.title.copy(color = colors.foregroundPrimary),
            modifier = Modifier.padding(top = spacing.small),
        )
        if (history.isEmpty()) {
            UndineEmptyState(message = texts.historyEmpty)
        } else {
            LazyColumn(modifier = Modifier.testTag(UndoTags.HISTORY)) {
                // OperationEntry는 기록 후 불변이며, 같은 기록은 같은 행 정체성을 갖는다.
                items(history, key = { it.entry }) { row -> UndoHistoryRowView(row) }
            }
        }
    }
}

@Composable
private fun UndoHistoryRowView(row: UndoHistoryRow) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        BasicText(
            text = row.entry.operation.label,
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        )
        BasicText(
            text = row.entry.targetLabel,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        BasicText(
            text = formatRecordedAt(row.entry.recordedAt, strings.locale),
            style = UndineTokens.typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = row.availability,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
    }
}

private fun handleUndoKey(event: KeyEvent, state: UndoState): Boolean =
    if (event.isUndoShortcut()) {
        state.undoFromKeyboard()
        true
    } else {
        false
    }

private fun KeyEvent.isUndoShortcut(): Boolean =
    type == KeyEventType.KeyDown && key == Key.Z && hasPrimaryModifier

private val KeyEvent.hasPrimaryModifier: Boolean
    get() = isCtrlPressed || isMetaPressed

/** 이력은 날짜만이 아니라 기록 시각까지 보여 준다. */
private fun formatRecordedAt(instant: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(instant.atZone(ZoneId.systemDefault()))
