package dev.undine.presentation.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.undine.domain.ThemeMode

/**
 * 현재 토큰 세트. 테마로 감싸지 않은 미리보기·테스트에서도 깨지지 않도록 라이트를 기본값으로 둔다 —
 * 예외를 던지면 화면 하나를 띄워보는 비용이 커진다.
 */
val LocalUndineTokens: ProvidableCompositionLocal<UndineTokenSet> =
    staticCompositionLocalOf { UndineTokenSet.Light }

/**
 * Composable 이 토큰을 읽는 단일 창구.
 *
 * ```kotlin
 * BasicText(text = hash, style = UndineTokens.typography.mono)
 * ```
 *
 * 색·간격·타이포는 **반드시 여기를 통해서만** 참조한다 (compose-ui 규칙 5).
 */
object UndineTokens {

    val color: ColorTokens
        @Composable @ReadOnlyComposable get() = LocalUndineTokens.current.color

    val spacing: SpacingTokens
        @Composable @ReadOnlyComposable get() = LocalUndineTokens.current.spacing

    val typography: TypographyTokens
        @Composable @ReadOnlyComposable get() = LocalUndineTokens.current.typography

    val shape: ShapeTokens
        @Composable @ReadOnlyComposable get() = LocalUndineTokens.current.shape
}

/**
 * 앱 트리를 감싸 토큰을 공급한다.
 *
 * @param themeMode 사용자가 설정에서 고른 값. 기본은 [ThemeMode.SYSTEM].
 * @param isSystemInDarkMode OS 다크 모드 판정. 기본값 [SystemDarkModeUnavailable] 은 판정 수단이
 *   없다는 뜻이며 라이트로 해석된다. 실제 판정 주입은 UND-26 소유다.
 */
@Composable
fun UndineTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    isSystemInDarkMode: () -> Boolean = SystemDarkModeUnavailable,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalUndineTokens provides undineTokensFor(themeMode, isSystemInDarkMode),
        content = content,
    )
}
