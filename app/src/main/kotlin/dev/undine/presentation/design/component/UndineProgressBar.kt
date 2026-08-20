package dev.undine.presentation.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.undine.presentation.design.UndineTokens

/**
 * fetch·clone 처럼 초 단위 작업의 진행 표시 (compose-ui 규칙 7).
 *
 * 최소 시그니처만 정한다 — 소비 티켓이 필요에 따라 파라미터를 추가한다 (결정 문서 UND-10).
 *
 * @param fraction 0.0~1.0. 범위 밖 값은 잘라낸다 — 진행률 계산이 튀어도 화면이 깨지지 않아야 한다.
 *   진행량을 알 수 없는 구간에서는 호출부가 직전 값을 유지한다 (결정 문서 UND-08 6항).
 */
@Composable
fun UndineProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val trackShape = RoundedCornerShape(UndineTokens.shape.cornerSmall)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(UndineTokens.spacing.extraSmall)
            .clip(trackShape)
            .background(colors.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(colors.accent),
        )
    }
}
