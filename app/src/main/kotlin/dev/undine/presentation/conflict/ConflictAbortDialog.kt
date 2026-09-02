package dev.undine.presentation.conflict

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.design.undineDialogSurface
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.conflict
import dev.undine.presentation.i18n.strings

/**
 * 중단 확인. **사라질 경로와 복구 불가성을 보여 준 뒤에만** 실행한다 — 중단은 워킹트리 편집을
 * 되돌리므로 사용자가 무엇을 잃는지 모른 채 누를 수 있으면 안 된다.
 *
 * [staleReason] 이 있으면 확인 뒤 편집이 늘어 거부된 것이다 — 갱신된 목록으로 다시 묻는다.
 */
@Composable
internal fun ConflictAbortDialog(
    paths: List<String>,
    staleReason: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing
    val texts = strings.conflict

    Column(
        modifier = modifier
            .background(colors.surface)
            .border(shape.borderThick, colors.warning, RoundedCornerShape(shape.cornerMedium))
            .undineDialogSurface(onDismiss = onDismiss)
            .padding(spacing.medium)
            .testTag(ConflictTags.ABORT_DIALOG),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        if (staleReason != null) {
            // 거부 사유(MergeService 문장)를 함께 보여 준다 — 왜 다시 묻는지가 안내에 있어야 한다.
            BasicText(
                text = "${texts.abortStale} $staleReason",
                modifier = Modifier.testTag(ConflictTags.ABORT_STALE),
                style = UndineTokens.typography.body.copy(color = colors.warning),
            )
        }
        BasicText(
            text = texts.abortConfirmTitle,
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        )
        BasicText(
            text = texts.abortConfirmPaths(paths.joinToString()),
            modifier = Modifier.testTag(ConflictTags.ABORT_PATHS),
            style = UndineTokens.typography.mono.copy(color = colors.foregroundSecondary),
        )
        BasicText(
            text = texts.abortConfirmIrreversible,
            style = UndineTokens.typography.caption.copy(color = colors.deletion),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = texts.abortConfirmAccept,
                onClick = onAccept,
                modifier = Modifier.testTag(ConflictTags.ABORT_ACCEPT),
            )
            UndineToolbarButton(
                label = strings.common.cancel,
                onClick = onDismiss,
                modifier = Modifier.testTag(ConflictTags.ABORT_CANCEL),
            )
        }
    }
}
