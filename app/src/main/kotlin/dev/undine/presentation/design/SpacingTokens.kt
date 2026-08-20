package dev.undine.presentation.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 간격 토큰. 전부 4dp 배수의 단일 스케일이다 — 화면마다 다른 여백이 생기지 않게 한다. */
data class SpacingTokens(
    val extraSmall: Dp,
    val small: Dp,
    val medium: Dp,
    val large: Dp,
    val extraLarge: Dp,
    val huge: Dp,
) {
    companion object {

        val Default = SpacingTokens(
            extraSmall = 4.dp,
            small = 8.dp,
            medium = 12.dp,
            large = 16.dp,
            extraLarge = 24.dp,
            huge = 32.dp,
        )
    }
}
