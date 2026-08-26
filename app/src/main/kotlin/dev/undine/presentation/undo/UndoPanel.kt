package dev.undine.presentation.undo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        UndoActionButton(button, onClick = state::undo)
        button.disabledReason?.let { reason ->
            BasicText(
                text = reason,
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
        }
        // 복구 불가 최상단을 지우는 경로가 없으면 그 아래의 되돌릴 수 있는 기록에 닿을 방법이 없다.
        // 저장소 상태로 막힌 기록에는 이 버튼을 내주지 않는다 — 해소되면 되돌릴 수 있는 기록이다.
        if (state.canDiscardBlocked) {
            UndineToolbarButton(
                label = texts.discardLabel,
                onClick = state::discardBlocked,
                modifier = Modifier.fillMaxWidth().testTag(UndoTags.DISCARD),
            )
        }
        undoLoadFailureMessage(state.loadFailure, strings)?.let { notice ->
            BasicText(
                text = notice,
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
                modifier = Modifier.testTag(UndoTags.LOAD_FAILURE),
            )
        }
        undoExecutionMessage(state.lastExecution, strings)?.let { message ->
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

/**
 * Undo 버튼과 그 툴팁.
 *
 * 툴팁은 **가리켰을 때 뜨는 진짜 툴팁**이어야 한다. 같은 문구를 버튼 아래 상시 노출하면 그건
 * 툴팁이 아니라 설명문이고, 툴바에 놓았을 때 줄만 늘린다. 마우스를 쓸 수 없는 경로를 위해 같은
 * 문구를 접근성 설명으로도 붙여 둔다 — 포커스로 읽는 사용자도 대상을 알 수 있어야 한다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UndoActionButton(button: UndoButtonPresentation, onClick: () -> Unit) {
    val tooltip = button.tooltip
    val buttonModifier = Modifier
        .fillMaxWidth()
        .semantics { if (tooltip != null) contentDescription = tooltip }
        .testTag(UndoTags.BUTTON)

    if (tooltip == null) {
        UndineToolbarButton(button.label, onClick, buttonModifier, button.enabled)
        return
    }

    TooltipArea(
        tooltip = { UndoTooltipBubble(tooltip) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        UndineToolbarButton(button.label, onClick, buttonModifier, button.enabled)
    }
}

@Composable
private fun UndoTooltipBubble(text: String) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape

    BasicText(
        text = text,
        style = UndineTokens.typography.caption.copy(color = colors.foregroundPrimary),
        modifier = Modifier
            .clip(RoundedCornerShape(shape.cornerSmall))
            .background(colors.surface)
            .border(shape.borderThin, colors.border, RoundedCornerShape(shape.cornerSmall))
            .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
            .testTag(UndoTags.TOOLTIP),
    )
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
