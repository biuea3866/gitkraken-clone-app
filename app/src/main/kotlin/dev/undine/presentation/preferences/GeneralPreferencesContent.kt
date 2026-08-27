package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 일반 탭 — 테마·언어·시작 동작·확인 표시.
 *
 * **항목은 아직 비어 있다.** 채우는 것은 후속 티켓이며, 그때 [PreferencesRow] 계약으로 행을
 * 조립하고 값 변경은 [PreferencesState.apply] 로 넘긴다. 자리를 파일로 미리 잡아 두는 이유는
 * 탭 여섯 개가 같은 화면 파일을 동시에 고치지 않게 하기 위해서다.
 *
 * **시그니처는 지금 확정한다.** 탭 티켓이 인자를 늘리면 호출부 `PreferencesScreen` 도 바뀌어야
 * 하는데 그 파일은 탭 티켓의 수정 대상이 아니다. 그래서 여섯 탭이 지금 같은 모양으로 상태와
 * 문구를 받고, 자기 탭이 쓰는 외부 의존만 뒤에 덧붙인다. 일반 탭은 추가 의존이 없다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun GeneralPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
