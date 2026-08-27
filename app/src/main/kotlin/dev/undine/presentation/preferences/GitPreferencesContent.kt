package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Git 탭 — 저장소 git 설정이 실효값인 항목(커밋 서명 등).
 *
 * **아직 비어 있다.** 서명 항목은 앱 설정을 만들지 않고 `signingPreferencesRows` 로 git 설정의
 * 실효값과 출처만 읽기 전용으로 보여준다 — 앱이 사본을 두면 사용자가 `git config` 로 바꾼 값과
 * 어긋난다. 채우는 것은 후속 티켓이다.
 */
@Composable
fun GitPreferencesContent(modifier: Modifier = Modifier) {
    PreferencesTabPlaceholder(modifier)
}
