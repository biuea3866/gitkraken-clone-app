package dev.undine.presentation.preferences

import dev.undine.presentation.i18n.PreferencesStrings

/**
 * 환경설정 화면의 탭. 탭마다 붙는 상위 계약이 달라(앱 설정·git 설정·신원·외부 도구·단축키)
 * 내용은 후속 티켓이 각자 채우고, 골격은 **경계와 전환**만 정한다.
 */
enum class PreferencesTab {
    GENERAL,
    GIT,
    ACCOUNTS,
    TOOLS,
    SHORTCUTS,
    ADVANCED,
}

/** 탭 이름. 문자열은 리소스에서만 온다. */
fun PreferencesTab.titleIn(texts: PreferencesStrings): String = when (this) {
    PreferencesTab.GENERAL -> texts.tabGeneral
    PreferencesTab.GIT -> texts.tabGit
    PreferencesTab.ACCOUNTS -> texts.tabAccounts
    PreferencesTab.TOOLS -> texts.tabTools
    PreferencesTab.SHORTCUTS -> texts.tabShortcuts
    PreferencesTab.ADVANCED -> texts.tabAdvanced
}

/**
 * 좌우 방향키로 옮겨 갈 탭. 목록 끝에서 **순환**하므로 키보드만으로 여섯 탭에 모두 닿는다
 * (마우스 전용 경로를 만들지 않는다).
 */
fun PreferencesTab.shifted(by: Int): PreferencesTab {
    val tabs = PreferencesTab.entries
    val next = (tabs.indexOf(this) + by).mod(tabs.size)
    return tabs[next]
}
