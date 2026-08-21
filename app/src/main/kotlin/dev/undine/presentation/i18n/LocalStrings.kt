package dev.undine.presentation.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 화면 트리에 제공되는 문자열 조회 API.
 *
 * **`App.kt` 배선은 UND-26 소유**다 — 이 티켓은 정의까지만 한다. 제공자가 없어도
 * 시스템 로케일 기준 기본값이 쓰이므로 미배선 상태에서 문자열이 사라지지 않는다.
 *
 * 로케일은 화면 수명 동안 거의 바뀌지 않으므로 `staticCompositionLocalOf` 를 쓴다 —
 * 값이 바뀌면 하위 트리 전체가 재구성되는 대신 읽기 비용이 없다.
 */
val LocalStrings: ProvidableCompositionLocal<Strings> = staticCompositionLocalOf { systemStrings() }

/** Composable 안에서 `strings.common.ok` 형태로 읽는 진입점. */
val strings: Strings
    @Composable
    @ReadOnlyComposable
    get() = LocalStrings.current
