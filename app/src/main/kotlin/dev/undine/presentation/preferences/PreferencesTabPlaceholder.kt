package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.i18n.preferences
import dev.undine.presentation.i18n.strings

/**
 * 아직 채워지지 않은 탭의 자리 표시.
 *
 * **티켓 번호를 화면에 드러내지 않는다** — 사용자에게 "UND-NN 에서 채운다" 는 아무 뜻이 없다.
 * 어느 티켓이 채우는지는 각 스텁 파일의 KDoc 이 말한다.
 */
@Composable
fun PreferencesTabPlaceholder(modifier: Modifier = Modifier) {
    UndineEmptyState(message = strings.preferences.comingSoon, modifier = modifier)
}
