package dev.undine.presentation.design.component

import androidx.compose.ui.graphics.Color
import dev.undine.presentation.design.ColorTokens

/** 토스트가 전하는 소식의 성격. 색만으로 구분되지 않도록 문구 자체가 상황을 설명해야 한다. */
enum class UndineToastTone {
    NEUTRAL,
    WARNING,
    ERROR,
    ;

    internal fun accentOf(colors: ColorTokens): Color = when (this) {
        NEUTRAL -> colors.accent
        WARNING -> colors.warning
        ERROR -> colors.deletion
    }
}
