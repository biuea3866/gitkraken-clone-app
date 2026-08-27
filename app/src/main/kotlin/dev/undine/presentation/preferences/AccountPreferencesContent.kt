package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.application.identity.IdentityUseCases
import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 계정 탭 — 작성자 신원 프로필과 저장소별 매핑.
 *
 * **항목은 아직 비어 있다.** 프로필 관리는 후속 티켓이 채운다. 전체 초기화가 프로필을 지우지
 * 않는다는 규칙은 이미 골격이 세워 뒀다 — 이 탭은 항목별 복원만 내준다.
 *
 * 프로필 CRUD 는 설정 파일이 아니라 신원 서비스가 소유하므로 [identity] 를 추가로 받는다.
 * 묶음으로 받는 이유는 [IdentityUseCases] 의 KDoc 에 있다 — 쓰는 동작이 늘어도 호출부
 * `PreferencesScreen` 이 바뀌지 않아야 한다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun AccountPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    identity: IdentityUseCases,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
