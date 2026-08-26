package dev.undine.presentation.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * 셸의 네 영역에 꽂히는 슬롯 묶음.
 *
 * 각 슬롯은 [AppShellSelection] 스냅샷을 받아 **읽기만** 한다 — 선택을 바꾸는 콜백은 배선 코드(UND-26)가
 * 슬롯 람다 안에서 직접 넘긴다. 셸은 슬롯이 무엇을 그리는지 알지 못한다.
 *
 * 기본값은 빈 슬롯이다 — 배선 전에도 셸 골격만 띄워볼 수 있어야 한다.
 */
@Stable
class AppShellSlots(
    val toolbar: @Composable (AppShellSelection) -> Unit = {},
    val tabs: @Composable () -> Unit = {},
    val sidebar: @Composable (AppShellSelection) -> Unit = {},
    val center: @Composable (AppShellSelection) -> Unit = {},
    val bottom: @Composable (AppShellSelection) -> Unit = {},
)
