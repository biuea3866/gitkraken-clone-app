package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.palette.CommandRegistry

/**
 * 단축키 탭 — 명령별 단축키 재지정과 충돌 표시.
 *
 * **항목은 아직 비어 있다.** 저장·복원·런타임 적용 경로는 골격이 이미 세웠다
 * (`Settings.shortcutOverrides` · `CommandRegistry.applyShortcutOverrides`). 이 탭은 재지정 UI 와
 * 충돌 해소, 등록되지 않은 명령의 오버라이드를 어떻게 다룰지를 채운다.
 *
 * 명령 목록·실효 단축키·충돌 조회는 [commands] 만이 알고 있으므로 추가로 받는다 — 레지스트리는
 * 팔레트와 단축키 처리기가 보는 유일한 조회원이라, 탭이 자기 사본을 만들면 충돌 판정이 갈린다.
 */
// 스텁이라 아직 읽지 않는다. 지우면 탭 티켓이 시그니처를 바꿔야 하고, 그러면 수정할 수 없는
// `PreferencesScreen` 까지 바뀐다 — 이 티켓이 없애려던 바로 그 모순이다. 탭이 채우면 사라진다.
@Suppress("UnusedParameter")
@Composable
fun ShortcutPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    commands: CommandRegistry,
    modifier: Modifier = Modifier,
) {
    PreferencesTabPlaceholder(texts, modifier)
}
