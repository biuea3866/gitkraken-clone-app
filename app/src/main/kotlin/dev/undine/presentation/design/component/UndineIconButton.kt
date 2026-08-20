package dev.undine.presentation.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import dev.undine.presentation.design.UndineTokens

/**
 * 아이콘 하나만 있는 버튼.
 *
 * 최소 시그니처만 정한다 — 소비 티켓이 필요에 따라 파라미터를 추가한다 (결정 문서 UND-10).
 *
 * @param contentDescription 스크린 리더가 읽을 동작 설명. 아이콘 버튼은 글자가 없으므로 생략할 수 없다.
 */
@Composable
fun UndineIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(UndineTokens.shape.cornerSmall))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(spacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(
                if (enabled) colors.foregroundPrimary else colors.foregroundTertiary,
            ),
        )
    }
}
