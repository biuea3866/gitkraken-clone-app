package dev.undine.presentation.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.undine.presentation.design.UndineTokens

/**
 * 짧게 떴다 사라지는 알림.
 *
 * 표시 시간·배치·큐잉은 이 컴포넌트가 소유하지 않는다 — 상태 홀더가 정하고 호출부가 배치한다
 * (compose-ui 규칙 1). 최소 시그니처만 정한다 (결정 문서 UND-10).
 */
@Composable
fun UndineToast(
    message: String,
    modifier: Modifier = Modifier,
    tone: UndineToastTone = UndineToastTone.NEUTRAL,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val toastShape = RoundedCornerShape(shape.cornerMedium)

    Box(
        modifier = modifier
            .clip(toastShape)
            .background(colors.surface)
            .border(shape.borderThick, tone.accentOf(colors), toastShape)
            .padding(horizontal = spacing.medium, vertical = spacing.small),
    ) {
        BasicText(
            text = message,
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        )
    }
}
