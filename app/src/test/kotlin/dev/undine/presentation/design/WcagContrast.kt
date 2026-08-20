package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.1 상대 휘도(relative luminance)와 대비비(contrast ratio) 계산.
 *
 * 검증 전용이라 테스트 소스에만 둔다 — 런타임 코드는 대비를 계산하지 않고 확정된 토큰 값을 쓴다.
 * 공식: https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
object WcagContrast {

    private const val SRGB_LINEAR_THRESHOLD = 0.03928
    private const val SRGB_LINEAR_DIVISOR = 12.92
    private const val SRGB_GAMMA_OFFSET = 0.055
    private const val SRGB_GAMMA_DIVISOR = 1.055
    private const val SRGB_GAMMA_EXPONENT = 2.4
    private const val LUMINANCE_RED = 0.2126
    private const val LUMINANCE_GREEN = 0.7152
    private const val LUMINANCE_BLUE = 0.0722
    private const val CONTRAST_OFFSET = 0.05

    fun relativeLuminance(color: Color): Double =
        LUMINANCE_RED * linearize(color.red) +
            LUMINANCE_GREEN * linearize(color.green) +
            LUMINANCE_BLUE * linearize(color.blue)

    fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        val brighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (brighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET)
    }

    private fun linearize(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= SRGB_LINEAR_THRESHOLD) {
            value / SRGB_LINEAR_DIVISOR
        } else {
            ((value + SRGB_GAMMA_OFFSET) / SRGB_GAMMA_DIVISOR).pow(SRGB_GAMMA_EXPONENT)
        }
    }
}
