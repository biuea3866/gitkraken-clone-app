package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 도구 탭 — 외부 diff/merge 도구.
 *
 * **아직 비어 있다.** git 의 `diff.tool`·`merge.tool` 이 우선이고 앱 설정은 차선값이라,
 * 실효값 출처 표시가 이 탭의 핵심이 된다. 채우는 것은 후속 티켓이다.
 */
@Composable
fun ToolPreferencesContent(modifier: Modifier = Modifier) {
    PreferencesTabPlaceholder(modifier)
}
