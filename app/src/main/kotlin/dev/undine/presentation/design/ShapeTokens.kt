package dev.undine.presentation.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 모서리 반경·경계 두께 토큰. `RoundedCornerShape` 는 사용처에서 이 값으로 만든다. */
data class ShapeTokens(
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val borderThin: Dp,
    val borderThick: Dp,
) {
    companion object {

        val Default = ShapeTokens(
            cornerSmall = 4.dp,
            cornerMedium = 8.dp,
            cornerLarge = 12.dp,
            borderThin = 1.dp,
            borderThick = 2.dp,
        )
    }
}
