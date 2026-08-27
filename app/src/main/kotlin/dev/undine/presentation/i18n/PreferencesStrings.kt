package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `preferences.*` 네임스페이스 — 환경설정 화면(테마·언어·시작 동작·신원 프로필·외부 도구)의 문구.
 *
 * **6개 탭의 문구를 골격이 한 번에 채운다.** 탭마다 이 파일을 고치면 같은 파일을 여섯 번 고치게
 * 되므로, UND-63 이 wave 8 i18n 스텁을 선행 제공한 것과 같은 이유로 탭 티켓은 이 파일을 수정하지
 * 않고 여기 있는 키를 조립해 쓴다.
 *
 * 구조는 [CommonStrings] 가 정본이다: [PREFERENCES_NAMESPACE] 로 키를 만들고, 번역 맵을 로케일별로
 * 채우고, `Strings.preferences` 확장 프로퍼티로 노출한다. 공통 파일(`BuiltInStrings.kt`)은 이미
 * 등록돼 있으므로 건드리지 않는다.
 */
internal const val PREFERENCES_NAMESPACE: String = "preferences"

/** 환경설정 화면의 `preferences.*` 키. */
object PreferencesKeys {
    val title = StringKey("$PREFERENCES_NAMESPACE.title")
    val tabGeneral = StringKey("$PREFERENCES_NAMESPACE.tabGeneral")
    val tabGit = StringKey("$PREFERENCES_NAMESPACE.tabGit")
    val tabAccounts = StringKey("$PREFERENCES_NAMESPACE.tabAccounts")
    val tabTools = StringKey("$PREFERENCES_NAMESPACE.tabTools")
    val tabShortcuts = StringKey("$PREFERENCES_NAMESPACE.tabShortcuts")
    val tabAdvanced = StringKey("$PREFERENCES_NAMESPACE.tabAdvanced")
    val comingSoon = StringKey("$PREFERENCES_NAMESPACE.comingSoon")
    val sourceApp = StringKey("$PREFERENCES_NAMESPACE.sourceApp")
    val sourceGit = StringKey("$PREFERENCES_NAMESPACE.sourceGit")
    val restoreDefault = StringKey("$PREFERENCES_NAMESPACE.restoreDefault")
    val resetAll = StringKey("$PREFERENCES_NAMESPACE.resetAll")
    val resetAllWarning = StringKey("$PREFERENCES_NAMESPACE.resetAllWarning")
    val resetAllConfirm = StringKey("$PREFERENCES_NAMESPACE.resetAllConfirm")
    val resetAllCancel = StringKey("$PREFERENCES_NAMESPACE.resetAllCancel")
    val loadFailed = StringKey("$PREFERENCES_NAMESPACE.loadFailed")
    val saveFailed = StringKey("$PREFERENCES_NAMESPACE.saveFailed")
    val enabled = StringKey("$PREFERENCES_NAMESPACE.enabled")
    val disabled = StringKey("$PREFERENCES_NAMESPACE.disabled")
    val theme = StringKey("$PREFERENCES_NAMESPACE.theme")
    val themeLight = StringKey("$PREFERENCES_NAMESPACE.themeLight")
    val themeDark = StringKey("$PREFERENCES_NAMESPACE.themeDark")
    val themeSystem = StringKey("$PREFERENCES_NAMESPACE.themeSystem")
    val language = StringKey("$PREFERENCES_NAMESPACE.language")
    val languageSystem = StringKey("$PREFERENCES_NAMESPACE.languageSystem")
    val reopenLastRepository = StringKey("$PREFERENCES_NAMESPACE.reopenLastRepository")
    val confirmDestructiveActions = StringKey("$PREFERENCES_NAMESPACE.confirmDestructiveActions")
    val signCommits = StringKey("$PREFERENCES_NAMESPACE.signCommits")
    val signTags = StringKey("$PREFERENCES_NAMESPACE.signTags")
    val signingFormat = StringKey("$PREFERENCES_NAMESPACE.signingFormat")
    val signingKey = StringKey("$PREFERENCES_NAMESPACE.signingKey")
    val signingKeyUnset = StringKey("$PREFERENCES_NAMESPACE.signingKeyUnset")
    val identityProfiles = StringKey("$PREFERENCES_NAMESPACE.identityProfiles")
    val identityProfilesEmpty = StringKey("$PREFERENCES_NAMESPACE.identityProfilesEmpty")
    val diffTool = StringKey("$PREFERENCES_NAMESPACE.diffTool")
    val mergeTool = StringKey("$PREFERENCES_NAMESPACE.mergeTool")
    val toolUnset = StringKey("$PREFERENCES_NAMESPACE.toolUnset")
    val shortcutCommand = StringKey("$PREFERENCES_NAMESPACE.shortcutCommand")
    val shortcutBinding = StringKey("$PREFERENCES_NAMESPACE.shortcutBinding")
    val shortcutDefault = StringKey("$PREFERENCES_NAMESPACE.shortcutDefault")
    val shortcutOverridden = StringKey("$PREFERENCES_NAMESPACE.shortcutOverridden")
    val updateCheck = StringKey("$PREFERENCES_NAMESPACE.updateCheck")
    val updateCheckInterval = StringKey("$PREFERENCES_NAMESPACE.updateCheckInterval")
}

/** 환경설정 문구 접근자. `strings.preferences.title` 로 읽는다. */
@JvmInline
value class PreferencesStrings internal constructor(private val strings: Strings) {
    val title: String get() = strings.text(PreferencesKeys.title)
    val tabGeneral: String get() = strings.text(PreferencesKeys.tabGeneral)
    val tabGit: String get() = strings.text(PreferencesKeys.tabGit)
    val tabAccounts: String get() = strings.text(PreferencesKeys.tabAccounts)
    val tabTools: String get() = strings.text(PreferencesKeys.tabTools)
    val tabShortcuts: String get() = strings.text(PreferencesKeys.tabShortcuts)
    val tabAdvanced: String get() = strings.text(PreferencesKeys.tabAdvanced)
    val comingSoon: String get() = strings.text(PreferencesKeys.comingSoon)
    val sourceApp: String get() = strings.text(PreferencesKeys.sourceApp)
    val sourceGit: String get() = strings.text(PreferencesKeys.sourceGit)
    val restoreDefault: String get() = strings.text(PreferencesKeys.restoreDefault)
    val resetAll: String get() = strings.text(PreferencesKeys.resetAll)
    val resetAllWarning: String get() = strings.text(PreferencesKeys.resetAllWarning)
    val resetAllConfirm: String get() = strings.text(PreferencesKeys.resetAllConfirm)
    val resetAllCancel: String get() = strings.text(PreferencesKeys.resetAllCancel)
    val loadFailed: String get() = strings.text(PreferencesKeys.loadFailed)
    val saveFailed: String get() = strings.text(PreferencesKeys.saveFailed)
    val enabled: String get() = strings.text(PreferencesKeys.enabled)
    val disabled: String get() = strings.text(PreferencesKeys.disabled)
    val theme: String get() = strings.text(PreferencesKeys.theme)
    val themeLight: String get() = strings.text(PreferencesKeys.themeLight)
    val themeDark: String get() = strings.text(PreferencesKeys.themeDark)
    val themeSystem: String get() = strings.text(PreferencesKeys.themeSystem)
    val language: String get() = strings.text(PreferencesKeys.language)
    val languageSystem: String get() = strings.text(PreferencesKeys.languageSystem)
    val reopenLastRepository: String get() = strings.text(PreferencesKeys.reopenLastRepository)
    val confirmDestructiveActions: String get() = strings.text(PreferencesKeys.confirmDestructiveActions)
    val signCommits: String get() = strings.text(PreferencesKeys.signCommits)
    val signTags: String get() = strings.text(PreferencesKeys.signTags)
    val signingFormat: String get() = strings.text(PreferencesKeys.signingFormat)
    val signingKey: String get() = strings.text(PreferencesKeys.signingKey)
    val signingKeyUnset: String get() = strings.text(PreferencesKeys.signingKeyUnset)
    val identityProfiles: String get() = strings.text(PreferencesKeys.identityProfiles)
    val identityProfilesEmpty: String get() = strings.text(PreferencesKeys.identityProfilesEmpty)
    val diffTool: String get() = strings.text(PreferencesKeys.diffTool)
    val mergeTool: String get() = strings.text(PreferencesKeys.mergeTool)
    val toolUnset: String get() = strings.text(PreferencesKeys.toolUnset)
    val shortcutCommand: String get() = strings.text(PreferencesKeys.shortcutCommand)
    val shortcutBinding: String get() = strings.text(PreferencesKeys.shortcutBinding)
    val shortcutDefault: String get() = strings.text(PreferencesKeys.shortcutDefault)
    val shortcutOverridden: String get() = strings.text(PreferencesKeys.shortcutOverridden)
    val updateCheck: String get() = strings.text(PreferencesKeys.updateCheck)
    val updateCheckInterval: String get() = strings.text(PreferencesKeys.updateCheckInterval)
}

/** 환경설정 문구 네임스페이스 진입점. */
val Strings.preferences: PreferencesStrings get() = PreferencesStrings(this)

internal val preferencesTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        PreferencesKeys.title to "환경설정",
        PreferencesKeys.tabGeneral to "일반",
        PreferencesKeys.tabGit to "Git",
        PreferencesKeys.tabAccounts to "계정",
        PreferencesKeys.tabTools to "도구",
        PreferencesKeys.tabShortcuts to "단축키",
        PreferencesKeys.tabAdvanced to "고급",
        PreferencesKeys.comingSoon to "준비 중입니다",
        PreferencesKeys.sourceApp to "앱 설정",
        PreferencesKeys.sourceGit to "git 설정",
        PreferencesKeys.restoreDefault to "기본값으로",
        PreferencesKeys.resetAll to "전체 초기화",
        PreferencesKeys.resetAllWarning to
            "테마·언어·시작 동작·확인 표시·단축키·업데이트 주기와 열린 탭이 기본값으로 돌아갑니다. " +
            "신원 프로필과 외부 도구 설정, 저장소의 git 설정은 그대로 둡니다. 되돌릴 수 없습니다.",
        PreferencesKeys.resetAllConfirm to "초기화",
        PreferencesKeys.resetAllCancel to "그대로 두기",
        PreferencesKeys.loadFailed to "설정을 읽지 못해 기본값으로 열었습니다",
        PreferencesKeys.saveFailed to "설정을 저장하지 못해 이전 값으로 되돌렸습니다",
        PreferencesKeys.enabled to "켜짐",
        PreferencesKeys.disabled to "꺼짐",
        PreferencesKeys.theme to "테마",
        PreferencesKeys.themeLight to "라이트",
        PreferencesKeys.themeDark to "다크",
        PreferencesKeys.themeSystem to "시스템 설정을 따름",
        PreferencesKeys.language to "언어",
        PreferencesKeys.languageSystem to "시스템 설정을 따름",
        PreferencesKeys.reopenLastRepository to "시작할 때 마지막 저장소 열기",
        PreferencesKeys.confirmDestructiveActions to "되돌릴 수 없는 작업 전에 확인",
        PreferencesKeys.signCommits to "커밋 서명",
        PreferencesKeys.signTags to "태그 서명",
        PreferencesKeys.signingFormat to "서명 형식",
        PreferencesKeys.signingKey to "서명 키",
        PreferencesKeys.signingKeyUnset to "지정되지 않음",
        PreferencesKeys.identityProfiles to "신원 프로필",
        PreferencesKeys.identityProfilesEmpty to "등록된 프로필이 없습니다",
        PreferencesKeys.diffTool to "외부 diff 도구",
        PreferencesKeys.mergeTool to "외부 merge 도구",
        PreferencesKeys.toolUnset to "설정되지 않음",
        PreferencesKeys.shortcutCommand to "명령",
        PreferencesKeys.shortcutBinding to "단축키",
        PreferencesKeys.shortcutDefault to "기본값",
        PreferencesKeys.shortcutOverridden to "변경됨",
        PreferencesKeys.updateCheck to "업데이트 확인",
        PreferencesKeys.updateCheckInterval to "확인 주기(시간)",
    ),
    Locale.ENGLISH to mapOf(
        PreferencesKeys.title to "Preferences",
        PreferencesKeys.tabGeneral to "General",
        PreferencesKeys.tabGit to "Git",
        PreferencesKeys.tabAccounts to "Accounts",
        PreferencesKeys.tabTools to "Tools",
        PreferencesKeys.tabShortcuts to "Shortcuts",
        PreferencesKeys.tabAdvanced to "Advanced",
        PreferencesKeys.comingSoon to "Coming soon",
        PreferencesKeys.sourceApp to "App settings",
        PreferencesKeys.sourceGit to "git config",
        PreferencesKeys.restoreDefault to "Restore default",
        PreferencesKeys.resetAll to "Reset all",
        PreferencesKeys.resetAllWarning to
            "Theme, language, startup behaviour, confirmations, shortcuts, update interval and open tabs " +
            "go back to their defaults. Identity profiles, external tools and repository git config are " +
            "left alone. This cannot be undone.",
        PreferencesKeys.resetAllConfirm to "Reset",
        PreferencesKeys.resetAllCancel to "Keep as is",
        PreferencesKeys.loadFailed to "Could not read settings — opened with defaults",
        PreferencesKeys.saveFailed to "Could not save — reverted to the previous value",
        PreferencesKeys.enabled to "On",
        PreferencesKeys.disabled to "Off",
        PreferencesKeys.theme to "Theme",
        PreferencesKeys.themeLight to "Light",
        PreferencesKeys.themeDark to "Dark",
        PreferencesKeys.themeSystem to "Follow system",
        PreferencesKeys.language to "Language",
        PreferencesKeys.languageSystem to "Follow system",
        PreferencesKeys.reopenLastRepository to "Reopen last repository on start",
        PreferencesKeys.confirmDestructiveActions to "Confirm before irreversible actions",
        PreferencesKeys.signCommits to "Sign commits",
        PreferencesKeys.signTags to "Sign tags",
        PreferencesKeys.signingFormat to "Signing format",
        PreferencesKeys.signingKey to "Signing key",
        PreferencesKeys.signingKeyUnset to "Not set",
        PreferencesKeys.identityProfiles to "Identity profiles",
        PreferencesKeys.identityProfilesEmpty to "No profiles yet",
        PreferencesKeys.diffTool to "External diff tool",
        PreferencesKeys.mergeTool to "External merge tool",
        PreferencesKeys.toolUnset to "Not configured",
        PreferencesKeys.shortcutCommand to "Command",
        PreferencesKeys.shortcutBinding to "Shortcut",
        PreferencesKeys.shortcutDefault to "Default",
        PreferencesKeys.shortcutOverridden to "Changed",
        PreferencesKeys.updateCheck to "Check for updates",
        PreferencesKeys.updateCheckInterval to "Check every (hours)",
    ),
)
