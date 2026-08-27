package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 아직 채워지지 않은 탭의 자리 표시.
 *
 * **티켓 번호를 화면에 드러내지 않는다** — 사용자에게 "UND-NN 에서 채운다" 는 아무 뜻이 없다.
 * 어느 티켓이 채우는지는 각 스텁 파일의 KDoc 이 말한다.
 *
 * 문구를 컴포지션 로컬에서 읽지 않고 [texts] 로 받는 이유는 탭 계약과 같은 모양을 쓰기 위해서다 —
 * 탭이 문구를 인자로 받으므로 자리 표시도 같은 문구 인스턴스를 그대로 넘겨받는다.
 */
@Composable
fun PreferencesTabPlaceholder(texts: PreferencesStrings, modifier: Modifier = Modifier) {
    UndineEmptyState(message = texts.comingSoon, modifier = modifier)
}
