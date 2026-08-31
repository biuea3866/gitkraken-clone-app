package dev.undine.presentation.preferences

import dev.undine.domain.ThemeMode

/**
 * 일반 탭의 선택지 하나 — 화면에 보일 문구와 고르면 저장될 값.
 *
 * [selected] 는 **지금 저장돼 있는 값인가**를 뜻한다. 사용자가 방금 누른 값이 아니다 — 화면은
 * 저장 결과로만 갱신하므로(결정 G12) 고른 값을 담아 둘 자리가 없다.
 */
internal data class GeneralPreferenceChoice<T>(
    val label: String,
    val value: T,
    val selected: Boolean,
)

/**
 * 아래 셋은 선택지를 고른 결과를 저장으로 옮긴다. `PreferencesState` 의 저장 경로를 그대로 쓰되
 * (`apply`) 어떤 설정이 바뀌는지는 이 탭이 정한다 — 그 홀더는 여섯 탭이 공유하므로 탭별 변경을
 * 거기에 쌓지 않는다.
 */
internal fun PreferencesState.selectTheme(theme: ThemeMode) {
    apply { it.copy(theme = theme) }
}

/** @param tag BCP 47 태그. `null` 이면 시스템 로케일을 따른다. */
internal fun PreferencesState.selectLanguage(tag: String?) {
    apply { it.copy(language = tag) }
}

internal fun PreferencesState.selectReopenLastRepository(reopen: Boolean) {
    apply { it.copy(reopenLastRepository = reopen) }
}
