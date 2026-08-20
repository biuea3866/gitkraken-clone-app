package dev.undine.presentation.design

import androidx.compose.ui.text.font.FontFamily
import dev.undine.domain.ThemeMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val SPACING_STEP = 4f

/** 테마 선택과 토큰 값 자체 — Compose 렌더링 없이 순수 값으로 검증한다. */
class UndineTokenSetSpec : FunSpec({

    test("LIGHT 를 고르면 라이트 토큰, DARK 를 고르면 다크 토큰이 나온다") {
        undineTokensFor(ThemeMode.LIGHT) { true } shouldBe UndineTokenSet.Light
        undineTokensFor(ThemeMode.DARK) { false } shouldBe UndineTokenSet.Dark
    }

    test("SYSTEM 은 시스템 판정 함수가 true 면 다크, false 면 라이트로 해석된다") {
        undineTokensFor(ThemeMode.SYSTEM) { true } shouldBe UndineTokenSet.Dark
        undineTokensFor(ThemeMode.SYSTEM) { false } shouldBe UndineTokenSet.Light
    }

    test("시스템 판정을 얻지 못하면 SYSTEM 은 라이트로 안전하게 해석된다") {
        SystemDarkModeUnavailable() shouldBe false
        undineTokensFor(ThemeMode.SYSTEM, SystemDarkModeUnavailable) shouldBe UndineTokenSet.Light
    }

    test("모든 색 토큰이 라이트와 다크에서 서로 다른 값으로 전환된다") {
        val light = namedColorsOf(UndineTokenSet.Light.color)
        val dark = namedColorsOf(UndineTokenSet.Dark.color)

        light.keys shouldBe dark.keys
        val unchanged = light.filter { (name, color) -> dark.getValue(name) == color }.keys
        unchanged shouldBe emptySet()
    }

    test("간격 토큰은 모두 4dp 배수이며 오름차순이다") {
        val steps = UndineTokenSet.Light.spacing.let {
            listOf(it.extraSmall, it.small, it.medium, it.large, it.extraLarge, it.huge)
        }
        steps.forEach { (it.value % SPACING_STEP) shouldBe 0f }
        steps.zipWithNext().forEach { (smaller, larger) -> larger.value shouldBeGreaterThan smaller.value }
    }

    test("고정폭 서체는 typography.mono 로만 제공되고 UI 서체는 고정폭이 아니다") {
        val typography = UndineTokenSet.Light.typography
        typography.mono.fontFamily shouldBe FontFamily.Monospace
        listOf(typography.title, typography.body, typography.caption).forEach {
            it.fontFamily shouldNotBe FontFamily.Monospace
        }
    }

    test("간격·타이포·형상 토큰은 모드와 무관하게 같다 — 전환되는 축은 색뿐이다") {
        UndineTokenSet.Dark.spacing shouldBe UndineTokenSet.Light.spacing
        UndineTokenSet.Dark.typography shouldBe UndineTokenSet.Light.typography
        UndineTokenSet.Dark.shape shouldBe UndineTokenSet.Light.shape
    }

    test("형상 토큰은 모서리 반경과 경계 두께를 양수로 제공한다") {
        val shape = UndineTokenSet.Light.shape
        listOf(shape.cornerSmall, shape.cornerMedium, shape.cornerLarge, shape.borderThin, shape.borderThick)
            .forEach { it.value shouldBeGreaterThan 0f }
    }

    test("그래프 레인 팔레트는 8~12색이다") {
        listOf(UndineTokenSet.Light, UndineTokenSet.Dark).forEach {
            it.color.lanePalette.size shouldBeGreaterThanOrEqual LANE_PALETTE_MIN_SIZE
            it.color.lanePalette.size shouldBeLessThanOrEqual LANE_PALETTE_MAX_SIZE
        }
        UndineTokenSet.Light.color.lanePalette shouldHaveSize UndineTokenSet.Dark.color.lanePalette.size
    }
})
