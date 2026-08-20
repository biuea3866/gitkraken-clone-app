package dev.undine.presentation.design

import dev.undine.domain.ThemeMode

/**
 * 한 테마 모드가 제공하는 토큰 전체. 색만 모드에 따라 바뀌고 간격·타이포·형상은 공통이다 —
 * 라이트/다크는 밝기 축의 차이지 레이아웃 축의 차이가 아니다.
 */
data class UndineTokenSet(
    val color: ColorTokens,
    val spacing: SpacingTokens,
    val typography: TypographyTokens,
    val shape: ShapeTokens,
) {
    companion object {

        val Light = UndineTokenSet(
            color = ColorTokens.Light,
            spacing = SpacingTokens.Default,
            typography = TypographyTokens.Default,
            shape = ShapeTokens.Default,
        )

        val Dark = UndineTokenSet(
            color = ColorTokens.Dark,
            spacing = SpacingTokens.Default,
            typography = TypographyTokens.Default,
            shape = ShapeTokens.Default,
        )
    }
}

/**
 * OS 다크 모드 판정을 **얻지 못하는** 경우의 기본 구현 — 라이트로 해석한다.
 *
 * Compose Desktop 이 제공하는 OS 테마 판정 수단을 이 티켓 범위에서 확인하지 못했다.
 * 실제 판정을 붙이는 것은 앱 배선을 소유한 UND-26 의 몫이며, 그때 [UndineTheme] 의
 * `isSystemInDarkMode` 인자로 주입하면 이 기본값을 대체할 수 있다.
 */
val SystemDarkModeUnavailable: () -> Boolean = { false }

/**
 * 사용자가 고른 [themeMode] 와 시스템 판정을 합쳐 토큰 세트를 고른다.
 *
 * Compose 없이 호출 가능한 순수 함수다 — 테마 선택 규칙을 렌더링 없이 검증하기 위해서다.
 *
 * @param isSystemInDarkMode [ThemeMode.SYSTEM] 일 때만 호출된다. 판정 수단이 없으면
 *   [SystemDarkModeUnavailable] 이 `false` 를 돌려주어 라이트로 안전하게 떨어진다.
 */
fun undineTokensFor(
    themeMode: ThemeMode,
    isSystemInDarkMode: () -> Boolean = SystemDarkModeUnavailable,
): UndineTokenSet = when (themeMode) {
    ThemeMode.LIGHT -> UndineTokenSet.Light
    ThemeMode.DARK -> UndineTokenSet.Dark
    ThemeMode.SYSTEM -> if (isSystemInDarkMode()) UndineTokenSet.Dark else UndineTokenSet.Light
}
