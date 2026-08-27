package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 단축키 탭 — 명령별 단축키 재지정과 충돌 표시.
 *
 * **아직 비어 있다.** 저장·복원·런타임 적용 경로는 골격이 이미 세웠다
 * (`Settings.shortcutOverrides` · `CommandRegistry.applyShortcutOverrides`). 이 탭은 재지정 UI 와
 * 충돌 해소, 등록되지 않은 명령의 오버라이드를 어떻게 다룰지를 채운다.
 */
@Composable
fun ShortcutPreferencesContent(modifier: Modifier = Modifier) {
    PreferencesTabPlaceholder(modifier)
}
