package dev.undine.presentation.rebase

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.rebase.RebasePreviewEntry
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.rebase
import dev.undine.presentation.i18n.strings

private const val SINGLE_LINE = 1
private const val MESSAGE_WIDTH_FRACTION = 0.6f

/**
 * 계획을 적용하면 이력이 어떻게 되는지.
 *
 * squash·fixup 으로 묶인 커밋을 **한 줄로 접어** 보여준다 — 계획 목록만 보면 어느 커밋이 어디에
 * 합쳐지는지 읽히지 않는다.
 */
@Composable
fun RebasePreviewPanel(entries: List<RebasePreviewEntry>, modifier: Modifier = Modifier) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val texts = strings.rebase

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(shape.borderThin, colors.divider, RoundedCornerShape(shape.cornerSmall))
            .padding(UndineTokens.spacing.small)
            .testTag(RebaseTags.PREVIEW),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        BasicText(
            text = texts.previewTitle,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        entries.forEachIndexed { index, entry ->
            PreviewRow(index = index, entry = entry)
        }
    }
}

@Composable
private fun PreviewRow(index: Int, entry: RebasePreviewEntry) {
    val colors = UndineTokens.color
    val texts = strings.rebase

    Row(
        modifier = Modifier.fillMaxWidth().testTag(RebaseTags.previewRow(index)),
        horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        val step = when (entry) {
            is RebasePreviewEntry.Kept -> entry.step
            is RebasePreviewEntry.Dropped -> entry.step
        }
        BasicText(
            text = step.commit.message.lineSequence().firstOrNull().orEmpty(),
            modifier = Modifier.fillMaxWidth(MESSAGE_WIDTH_FRACTION),
            style = UndineTokens.typography.body.copy(
                color = if (entry is RebasePreviewEntry.Dropped) colors.foregroundTertiary
                else colors.foregroundPrimary,
            ),
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
        )
        when (entry) {
            is RebasePreviewEntry.Dropped -> BasicText(
                text = texts.previewDropped,
                modifier = Modifier.testTag(RebaseTags.previewNote(index)),
                style = UndineTokens.typography.caption.copy(color = colors.deletion),
            )

            is RebasePreviewEntry.Kept -> if (entry.absorbed.isNotEmpty()) {
                BasicText(
                    text = texts.previewAbsorbed(entry.absorbed.size),
                    modifier = Modifier.testTag(RebaseTags.previewNote(index)),
                    style = UndineTokens.typography.caption.copy(color = colors.accent),
                )
            }
        }
    }
}
