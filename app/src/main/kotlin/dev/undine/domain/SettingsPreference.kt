package dev.undine.domain

/**
 * 설정 화면이 **하나씩 되돌릴 수 있는** 설정 항목.
 *
 * 항목별 "기본값으로" 와 전체 초기화가 같은 정의를 쓰게 하려고 한 자리에 모았다 —
 * 초기화 대상을 화면이 따로 나열하면 항목이 늘어날 때마다 두 곳이 어긋난다.
 */
enum class SettingsPreference {
    THEME,
    LANGUAGE,
    REOPEN_LAST_REPOSITORY,
    CONFIRM_DESTRUCTIVE_ACTIONS,
    SHORTCUT_OVERRIDES,
    UPDATE_CHECK,

    /** 열려 있던 저장소 탭과 활성 탭 위치. 다시 열면 되는 값이라 초기화 대상이다. */
    TAB_SESSION,

    /**
     * 작성자 신원 프로필. **전체 초기화 대상이 아니다** — 지우면 사용자가 다시 입력해야 하고
     * 되돌릴 수 없다. 이 항목만 명시적으로 고를 때 비운다.
     */
    IDENTITY_PROFILES,

    /** 외부 diff/merge 도구 설정. [IDENTITY_PROFILES] 와 같은 이유로 전체 초기화 대상이 아니다. */
    EXTERNAL_TOOLS,
}

/**
 * 전체 초기화가 되돌리는 항목 — **화면·동작 취향과 탭 세션뿐**이다.
 *
 * 신원 프로필과 외부 도구 경로는 빠져 있다. "전체 초기화" 를 누른 사람이 자격·경로 정보까지
 * 지우려 했다고 볼 수 없고, 지운 값은 되돌릴 수 없다. 저장소의 git 설정은 애초에 앱 설정이 아니라
 * 어떤 경우에도 이 경로가 건드리지 않는다.
 */
val RESET_ALL_PREFERENCES: List<SettingsPreference> = listOf(
    SettingsPreference.THEME,
    SettingsPreference.LANGUAGE,
    SettingsPreference.REOPEN_LAST_REPOSITORY,
    SettingsPreference.CONFIRM_DESTRUCTIVE_ACTIONS,
    SettingsPreference.SHORTCUT_OVERRIDES,
    SettingsPreference.UPDATE_CHECK,
    SettingsPreference.TAB_SESSION,
)

/** [preference] 한 항목만 기본값으로 되돌린 설정. 나머지 값은 그대로 둔다. */
fun Settings.withDefault(preference: SettingsPreference): Settings = when (preference) {
    SettingsPreference.THEME -> copy(theme = Settings.DEFAULT_THEME)
    SettingsPreference.LANGUAGE -> copy(language = Settings.DEFAULTS.language)
    SettingsPreference.REOPEN_LAST_REPOSITORY ->
        copy(reopenLastRepository = Settings.DEFAULTS.reopenLastRepository)

    SettingsPreference.CONFIRM_DESTRUCTIVE_ACTIONS ->
        copy(confirmDestructiveActions = Settings.DEFAULTS.confirmDestructiveActions)

    SettingsPreference.SHORTCUT_OVERRIDES -> copy(shortcutOverrides = Settings.DEFAULTS.shortcutOverrides)
    SettingsPreference.UPDATE_CHECK -> copy(updateCheck = UpdateCheckSettings.DEFAULT)
    SettingsPreference.TAB_SESSION -> copy(
        openTabs = Settings.DEFAULTS.openTabs,
        activeTabIndex = Settings.DEFAULTS.activeTabIndex,
    )

    SettingsPreference.IDENTITY_PROFILES -> copy(identityProfiles = Settings.DEFAULTS.identityProfiles)
    SettingsPreference.EXTERNAL_TOOLS -> copy(externalTools = ExternalToolSettings.NONE)
}

/**
 * 확인을 받은 전체 초기화의 결과.
 *
 * 항목별 복원을 접어 만든다 — 초기화가 항목별 복원과 다른 값을 쓰는 일이 생기지 않는다.
 */
fun Settings.withDefaultPreferences(): Settings =
    RESET_ALL_PREFERENCES.fold(this) { settings, preference -> settings.withDefault(preference) }
