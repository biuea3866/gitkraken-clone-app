package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 일반 탭 — 테마·언어·시작 동작·확인 표시.
 *
 * **아직 비어 있다.** 항목은 후속 티켓이 채우며, 그때 [PreferencesRow] 계약으로 행을 조립하고
 * 값 변경은 `PreferencesState.apply` 로 넘긴다(필요한 상태는 그 티켓이 인자로 받는다). 자리를
 * 파일로 미리 잡아 두는 이유는 탭 여섯 개가 같은 화면 파일을 동시에 고치지 않게 하기 위해서다.
 */
@Composable
fun GeneralPreferencesContent(modifier: Modifier = Modifier) {
    PreferencesTabPlaceholder(modifier)
}
