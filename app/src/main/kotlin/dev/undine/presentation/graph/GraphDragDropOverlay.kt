package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToast
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.graphDragDrop
import dev.undine.presentation.i18n.strings

/** 드롭 미리보기·확인·충돌 해결 안내를 상태 홀더 값만으로 렌더링한다. */
@Composable
internal fun GraphDragDropOverlay(
    state: GraphDragDropState,
    modifier: Modifier = Modifier,
) {
    val copy = strings.graphDragDrop
    state.preview?.let { preview ->
        UndineToast(
            message = preview.message,
            modifier = modifier,
            tone = if (preview.canDrop) UndineToastTone.NEUTRAL else UndineToastTone.WARNING,
        )
    }
    // 변경은 적용됐는데 Undo 항목만 없어진 경우다. 알리지 않으면 사용자는 되돌릴 수 있다고 믿는다.
    (state.outcome as? GraphDragDropUiOutcome.Completed)?.takeIf { it.undoRecordFailure != null }?.let {
        UndineToast(
            message = copy.undoRecordFailed,
            modifier = modifier,
            tone = UndineToastTone.WARNING,
        )
    }
    (state.outcome as? GraphDragDropUiOutcome.Conflicted)?.let { conflict ->
        UndineToast(
            message = copy.conflict(conflict.paths.joinToString()),
            modifier = modifier,
            tone = UndineToastTone.WARNING,
        )
    }
    state.confirmation?.let { confirmation ->
        GraphOperationConfirmationDialog(confirmation, state)
    }
}

@Composable
private fun GraphOperationConfirmationDialog(
    confirmation: GraphOperationConfirmation,
    state: GraphDragDropState,
) {
    val spacing = UndineTokens.spacing
    val colors = UndineTokens.color
    val shape = RoundedCornerShape(UndineTokens.shape.cornerMedium)
    val common = strings.common

    Dialog(onDismissRequest = state::cancelConfirmation) {
        Column(
            modifier = Modifier
                .background(colors.background, shape)
                .padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            BasicText(
                text = confirmation.warning ?: strings.graphDragDrop.confirm,
                style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            )
            if (confirmation.selected == null) {
                confirmation.choices.forEach { operation ->
                    UndineToolbarButton(
                        label = strings.graphDragDrop.commandFor(operation),
                        onClick = { state.choose(operation) },
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UndineToolbarButton(label = common.cancel, onClick = state::cancelConfirmation)
                    UndineToolbarButton(label = common.ok, onClick = state::confirm)
                }
            }
        }
    }
}

private fun dev.undine.presentation.i18n.GraphDragDropStrings.commandFor(
    operation: GraphOperation,
): String = when (operation) {
    is GraphOperation.Merge -> commandMerge
    is GraphOperation.Rebase -> commandRebase
    is GraphOperation.CherryPick -> commandCherryPick
    is GraphOperation.ResetBranch -> commandReset
    is GraphOperation.MoveTag -> commandMoveTag
}
