package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 도구 탭 — 외부 diff/merge 도구와 사용자 지정 명령, 탭 폭·고정폭 서체.
 *
 * **항목은 아직 비어 있다.** git 의 `diff.tool`·`merge.tool` 이 우선이고 앱 설정은 차선값이라,
 * 실효값 출처 표시가 이 탭의 핵심이 된다. 탭 폭·고정폭 서체는 앱 설정에 자리가 있다
 * ([PreferencesState.settings] 의 `tabWidth`·`monospaceFontFamily`). 채우는 것은 후속 티켓이다.
 *
 * 설정한 도구를 실제로 띄워 확인하는 경로가 필요하므로 [externalTools] 를 추가로 받는다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun ToolPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    externalTools: ExternalToolUseCases,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
