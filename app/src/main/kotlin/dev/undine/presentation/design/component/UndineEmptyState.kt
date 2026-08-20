package dev.undine.presentation.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.undine.presentation.design.UndineTokens

/**
 * "표시할 커밋 없음" 처럼 목록이 비었을 때의 안내.
 *
 * 최소 시그니처만 정한다 — 소비 티켓이 필요에 따라 파라미터를 추가한다 (결정 문서 UND-10).
 */
@Composable
fun UndineEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(
        modifier = modifier.padding(spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = message,
            style = typography.body.copy(color = colors.foregroundSecondary),
        )
        if (description != null) {
            BasicText(
                text = description,
                style = typography.caption.copy(color = colors.foregroundTertiary),
            )
        }
    }
}
