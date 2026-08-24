package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color

const val LANE_PALETTE_MIN_SIZE = 8
const val LANE_PALETTE_MAX_SIZE = 12

/** WCAG 2.1 본문 텍스트 최소 대비비. */
const val TEXT_CONTRAST_MINIMUM = 4.5

/** WCAG 2.1 경계·비텍스트 최소 대비비. */
const val NON_TEXT_CONTRAST_MINIMUM = 3.0

/** 본문 텍스트로 쓰이는 전경 3단계. 배경·표면 양쪽에서 [TEXT_CONTRAST_MINIMUM] 을 넘어야 한다. */
fun textColorsOf(colors: ColorTokens): Map<String, Color> = mapOf(
    "foregroundPrimary" to colors.foregroundPrimary,
    "foregroundSecondary" to colors.foregroundSecondary,
    "foregroundTertiary" to colors.foregroundTertiary,
)

/** 경계·강조·상태색. 배경·표면 양쪽에서 [NON_TEXT_CONTRAST_MINIMUM] 을 넘어야 한다. */
fun nonTextColorsOf(colors: ColorTokens): Map<String, Color> = mapOf(
    "border" to colors.border,
    "accent" to colors.accent,
    "addition" to colors.addition,
    "deletion" to colors.deletion,
    "conflict" to colors.conflict,
    "warning" to colors.warning,
)

/** 라이트·다크 전환 검증용 — 색 토큰 전부(레인 팔레트 포함). */
fun namedColorsOf(colors: ColorTokens): Map<String, Color> =
    mapOf(
        "background" to colors.background,
        "divider" to colors.divider,
        "surface" to colors.surface,
        "additionSurface" to colors.additionSurface,
        "deletionSurface" to colors.deletionSurface,
    ) +
        textColorsOf(colors) +
        nonTextColorsOf(colors) +
        colors.lanePalette.withIndex().associate { (index, color) -> "lane$index" to color }
