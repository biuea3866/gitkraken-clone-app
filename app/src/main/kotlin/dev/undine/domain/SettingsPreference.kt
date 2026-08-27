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

    /**
     * 아래 일곱은 UND-74 가 탭 6건(Git·도구·고급)을 위해 더한 값이다. 프로필·도구 경로와 달리
     * **되돌려도 사용자가 다시 입력할 것이 없는 취향**이라 전체 초기화 대상에 함께 들어간다.
     */
    DEFAULT_BRANCH_NAME,
    PULL_STRATEGY,
    AUTOMATIC_FETCH,
    TAB_WIDTH,
    MONOSPACE_FONT,
    LARGE_FILE_THRESHOLD,
    COMMIT_PAGE_SIZE,
}

/**
 * 전체 초기화가 되돌리는 항목 — **화면·동작 취향과 탭 세션, 그리고 탭 값(Git·도구·고급)** 이다.
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
    SettingsPreference.DEFAULT_BRANCH_NAME,
    SettingsPreference.PULL_STRATEGY,
    SettingsPreference.AUTOMATIC_FETCH,
    SettingsPreference.TAB_WIDTH,
    SettingsPreference.MONOSPACE_FONT,
    SettingsPreference.LARGE_FILE_THRESHOLD,
    SettingsPreference.COMMIT_PAGE_SIZE,
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

    SettingsPreference.DEFAULT_BRANCH_NAME,
    SettingsPreference.PULL_STRATEGY,
    SettingsPreference.AUTOMATIC_FETCH,
    SettingsPreference.TAB_WIDTH,
    SettingsPreference.MONOSPACE_FONT,
    SettingsPreference.LARGE_FILE_THRESHOLD,
    SettingsPreference.COMMIT_PAGE_SIZE,
    -> withDefaultTabValue(preference)
}

/**
 * UND-74 가 더한 탭 값(Git·도구·고급)의 복원.
 *
 * [withDefault] 에서 나눈 이유는 한 `when` 이 열일곱 갈래가 되면 순환 복잡도 상한에 걸려서다.
 * 갈래를 줄이려고 `else` 로 접지 않는다 — 항목이 늘 때 컴파일러가 빠진 자리를 잡아야 한다.
 * 이 함수가 다루지 않는 항목은 [withDefault] 가 이미 처리했으므로 그대로 돌려준다.
 */
private fun Settings.withDefaultTabValue(preference: SettingsPreference): Settings = when (preference) {
    SettingsPreference.DEFAULT_BRANCH_NAME -> copy(defaultBranchName = Settings.DEFAULT_BRANCH_NAME)
    SettingsPreference.PULL_STRATEGY -> copy(pullStrategy = Settings.DEFAULT_PULL_STRATEGY)
    SettingsPreference.AUTOMATIC_FETCH -> copy(automaticFetch = AutomaticFetchSettings.DEFAULT)
    SettingsPreference.TAB_WIDTH -> copy(tabWidth = Settings.DEFAULT_TAB_WIDTH)
    SettingsPreference.MONOSPACE_FONT -> copy(monospaceFontFamily = Settings.DEFAULTS.monospaceFontFamily)
    SettingsPreference.LARGE_FILE_THRESHOLD ->
        copy(largeFileThresholdBytes = Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES)

    SettingsPreference.COMMIT_PAGE_SIZE -> copy(commitPageSize = Settings.DEFAULT_COMMIT_PAGE_SIZE)

    SettingsPreference.THEME,
    SettingsPreference.LANGUAGE,
    SettingsPreference.REOPEN_LAST_REPOSITORY,
    SettingsPreference.CONFIRM_DESTRUCTIVE_ACTIONS,
    SettingsPreference.SHORTCUT_OVERRIDES,
    SettingsPreference.UPDATE_CHECK,
    SettingsPreference.TAB_SESSION,
    SettingsPreference.IDENTITY_PROFILES,
    SettingsPreference.EXTERNAL_TOOLS,
    -> this
}

/**
 * 확인을 받은 전체 초기화의 결과.
 *
 * 항목별 복원을 접어 만든다 — 초기화가 항목별 복원과 다른 값을 쓰는 일이 생기지 않는다.
 */
fun Settings.withDefaultPreferences(): Settings =
    RESET_ALL_PREFERENCES.fold(this) { settings, preference -> settings.withDefault(preference) }
