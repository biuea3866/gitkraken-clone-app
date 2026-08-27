package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 고급 탭 — 대용량 파일 임계치·커밋 페이지 크기·업데이트 확인 주기·로그 위치처럼 드물게 만지는 값.
 *
 * **항목은 아직 비어 있다.** 앱 설정 값은 이미 자리가 있다 ([PreferencesState.settings] 의
 * `largeFileThresholdBytes`·`commitPageSize`·`updateCheck`). 채우는 것은 후속 티켓이다.
 *
 * 시그니처 고정 이유는 [GeneralPreferencesContent] 와 같다. 고급 탭은 추가 의존이 없다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun AdvancedPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
