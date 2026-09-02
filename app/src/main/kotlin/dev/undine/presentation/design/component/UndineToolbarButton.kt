package dev.undine.presentation.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.undine.presentation.design.MinimumTargetSize
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.undineFocusRing

/**
 * 툴바에 놓는 글자 버튼.
 *
 * 최소 시그니처만 정한다 — 소비 티켓이 필요에 따라 파라미터를 추가한다 (결정 문서 UND-10).
 */
@Composable
fun UndineToolbarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape

    val buttonShape = RoundedCornerShape(shape.cornerSmall)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinimumTargetSize, minHeight = MinimumTargetSize)
            .clip(buttonShape)
            .background(colors.surface)
            .border(shape.borderThin, colors.border, buttonShape)
            .undineFocusRing(buttonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = UndineTokens.typography.body.copy(
                color = if (enabled) colors.foregroundPrimary else colors.foregroundTertiary,
            ),
        )
    }
}
