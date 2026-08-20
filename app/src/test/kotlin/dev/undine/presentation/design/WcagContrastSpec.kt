package dev.undine.presentation.design

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val TOLERANCE = 0.01
private val BLACK = Color(0xFF000000)
private val WHITE = Color(0xFFFFFFFF)
private val MID_GRAY = Color(0xFF767676)

/** 대비 판정의 근거가 되는 계산 자체를 먼저 못박는다 — 계산이 틀리면 아래 팔레트 검증이 전부 무의미하다. */
class WcagContrastSpec : FunSpec({

    test("검정과 흰색의 대비비는 21:1 이다") {
        WcagContrast.contrastRatio(BLACK, WHITE) shouldBe (21.0 plusOrMinus TOLERANCE)
    }

    test("같은 색끼리의 대비비는 1:1 이다") {
        WcagContrast.contrastRatio(MID_GRAY, MID_GRAY) shouldBe (1.0 plusOrMinus TOLERANCE)
    }

    test("대비비는 인자 순서와 무관하다") {
        WcagContrast.contrastRatio(MID_GRAY, WHITE) shouldBe
            (WcagContrast.contrastRatio(WHITE, MID_GRAY) plusOrMinus TOLERANCE)
    }

    test("흰색의 상대 휘도는 1, 검정은 0 이다") {
        WcagContrast.relativeLuminance(WHITE) shouldBe (1.0 plusOrMinus TOLERANCE)
        WcagContrast.relativeLuminance(BLACK) shouldBe (0.0 plusOrMinus TOLERANCE)
    }

    test("WCAG 경계 사례 #767676 은 흰 배경에서 4.5:1 을 겨우 넘는다") {
        val ratio = WcagContrast.contrastRatio(MID_GRAY, WHITE)
        ratio shouldBeGreaterThan 4.5
        ratio shouldBeLessThan 4.6
    }
})
