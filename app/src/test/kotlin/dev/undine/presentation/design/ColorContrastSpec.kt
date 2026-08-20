package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private val MODES = mapOf(
    "LIGHT" to UndineTokenSet.Light.color,
    "DARK" to UndineTokenSet.Dark.color,
)

/** 배경·표면 두 바닥색 위에서 검사한다. 검사 쌍 수를 못박아 목록이 비어 통과하는 일을 막는다. */
private const val SURFACES_PER_MODE = 2
private val LANE_PALETTE_SIZE = UndineTokenSet.Light.color.lanePalette.size

private data class ContrastCheck(val label: String, val ratio: Double, val minimum: Double)

/**
 * 결정 문서 UND-10: 대비 기준은 WCAG 2.1 상대 휘도 대비비다.
 * 본문 텍스트 4.5:1, 경계·비텍스트 3:1, 레인 팔레트는 순환 인접끼리 3:1.
 */
class ColorContrastSpec : FunSpec({

    test("본문 텍스트 토큰은 배경·표면 양쪽에서 4.5:1 이상이다") {
        val checks = onSurfaces(::textColorsOf, TEXT_CONTRAST_MINIMUM)

        checks.size shouldBe MODES.size * textColorsOf(UndineTokenSet.Light.color).size * SURFACES_PER_MODE
        checks.failures().shouldBeEmpty()
    }

    test("경계·강조·상태색 토큰은 배경·표면 양쪽에서 3:1 이상이다") {
        val checks = onSurfaces(::nonTextColorsOf, NON_TEXT_CONTRAST_MINIMUM)

        checks.size shouldBe MODES.size * nonTextColorsOf(UndineTokenSet.Light.color).size * SURFACES_PER_MODE
        checks.failures().shouldBeEmpty()
    }

    test("diff 기호는 자기 행 배경 위에서 4.5:1 이상으로 읽힌다") {
        val checks = MODES.flatMap { (mode, colors) ->
            DiffChangeMark.entries.map { mark ->
                ContrastCheck(
                    label = "$mode.${mark.name} 기호",
                    ratio = WcagContrast.contrastRatio(mark.foregroundOf(colors), mark.backgroundOf(colors)),
                    minimum = TEXT_CONTRAST_MINIMUM,
                )
            }
        }

        checks.size shouldBe MODES.size * DiffChangeMark.entries.size
        checks.failures().shouldBeEmpty()
    }

    test("그래프 레인 팔레트는 순환 인접 쌍이 라이트·다크 모두에서 3:1 이상이다") {
        val checks = MODES.flatMap { (mode, colors) ->
            val palette = colors.lanePalette
            palette.indices.map { index ->
                val next = (index + 1) % palette.size
                ContrastCheck(
                    label = "$mode.lane$index vs lane$next",
                    ratio = WcagContrast.contrastRatio(palette[index], palette[next]),
                    minimum = NON_TEXT_CONTRAST_MINIMUM,
                )
            }
        }

        checks.size shouldBe MODES.size * LANE_PALETTE_SIZE
        checks.failures().shouldBeEmpty()
    }
})

private fun onSurfaces(select: (ColorTokens) -> Map<String, Color>, minimum: Double): List<ContrastCheck> =
    MODES.flatMap { (mode, colors) ->
        select(colors).flatMap { (name, color) ->
            listOf("background" to colors.background, "surface" to colors.surface).map { (surfaceName, surface) ->
                ContrastCheck(
                    label = "$mode.$name on $surfaceName",
                    ratio = WcagContrast.contrastRatio(color, surface),
                    minimum = minimum,
                )
            }
        }
    }

private fun List<ContrastCheck>.failures(): List<String> =
    filter { it.ratio < it.minimum }
        .map { "${it.label} = %.2f:1 (최소 %.1f:1)".format(it.ratio, it.minimum) }
