package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.i18n.builtInStringCatalog

/**
 * 일반 탭 — 테마·언어·시작할 때 마지막 저장소 열기.
 *
 * 행은 공통 계약([appPreferencesRow]·[PreferencesRowItem])으로 조립하고, 값 변경은 전부
 * [PreferencesState.apply] 저장 경로로 들어간다. 표시 값과 선택지 판정은 [GeneralPreferenceChoice]
 * 쪽 순수 함수가 맡아 Compose 없이 검증된다.
 *
 * **고른 값을 미리 그리지 않는다.** 화면은 저장·읽기에 성공한 값만 보여주므로 저장이 거부되거나
 * 쓰기에 실패해도 되돌릴 대상이 없다 (결정 G12). 실패 문구는 셸의 공통 표시 경로가 낸다.
 *
 * **테마·언어의 앱 전역 전환은 여기서 끝나지 않는다.** `App.kt` 가 아직 테마와 문구를 하드코딩하고
 * 있어 저장된 값이 전체 화면에 닿지 않는다. 그 배선은 UND-51 소유다 — 이 탭은 저장·표시까지다.
 *
 * **시그니처는 UND-74 가 확정한 모양 그대로다.** 탭이 인자를 늘리면 호출부 [PreferencesScreen] 도
 * 바뀌어야 하는데 그 파일은 탭 티켓의 수정 대상이 아니다. 일반 탭은 추가 의존이 없다.
 */
@Composable
fun GeneralPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    // 내장 번역은 실행 중 바뀌지 않는다. 매 리컴포지션 네임스페이스 맵을 다시 합치지 않게 한 번만 만든다.
    val catalog = remember { builtInStringCatalog() }
    val settings = state.settings

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        PreferencesRowItem(themePreferencesRow(settings, texts), state::restoreDefault) {
            GeneralPreferenceChoices(themeChoices(settings, texts), state::selectTheme)
        }
        PreferencesRowItem(languagePreferencesRow(settings, catalog, texts), state::restoreDefault) {
            GeneralPreferenceChoices(languageChoices(settings, catalog, texts), state::selectLanguage)
        }
        PreferencesRowItem(reopenLastRepositoryRow(settings, texts), state::restoreDefault) {
            GeneralPreferenceChoices(
                reopenLastRepositoryChoices(settings, texts),
                state::selectReopenLastRepository,
            )
        }
    }
}

/**
 * 선택지를 나란히 놓은 값 편집기.
 *
 * 지금 저장돼 있는 값은 누를 수 없다 — 같은 값을 다시 쓰는 저장을 만들지 않고, 어느 것이 현재
 * 값인지도 함께 드러난다. 각 선택지는 `clickable` 이라 키보드만으로도 옮겨 다니며 고를 수 있다.
 */
@Composable
private fun <T> GeneralPreferenceChoices(
    choices: List<GeneralPreferenceChoice<T>>,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        choices.forEach { choice ->
            UndineToolbarButton(
                label = choice.label,
                onClick = { onSelect(choice.value) },
                enabled = !choice.selected,
            )
        }
    }
}
