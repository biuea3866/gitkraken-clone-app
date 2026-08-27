package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * Git 탭 — 기본 브랜치명·pull 방식·자동 fetch 주기와, git 설정이 실효값인 항목(커밋 서명 등).
 *
 * **항목은 아직 비어 있다.** 앱 설정 값은 [PreferencesState.settings] 에 자리가 있고
 * (`defaultBranchName`·`pullStrategy`·`automaticFetch`), 서명은 앱 설정을 만들지 않고
 * `signingPreferencesRows` 로 git 설정의 실효값과 출처만 읽기 전용으로 보여준다 — 앱이 사본을 두면
 * 사용자가 `git config` 로 바꾼 값과 어긋난다. 채우는 것은 후속 티켓이다.
 *
 * 시그니처 고정 이유는 [GeneralPreferencesContent] 와 같다. Git 탭은 추가 의존이 없다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun GitPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
