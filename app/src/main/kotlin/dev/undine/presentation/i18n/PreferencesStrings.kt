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
    val invalidValue = StringKey("$PREFERENCES_NAMESPACE.invalidValue")
    val defaultBranchName = StringKey("$PREFERENCES_NAMESPACE.defaultBranchName")
    val pullStrategy = StringKey("$PREFERENCES_NAMESPACE.pullStrategy")
    val pullStrategyMerge = StringKey("$PREFERENCES_NAMESPACE.pullStrategyMerge")
    val pullStrategyRebase = StringKey("$PREFERENCES_NAMESPACE.pullStrategyRebase")
    val automaticFetch = StringKey("$PREFERENCES_NAMESPACE.automaticFetch")
    val automaticFetchInterval = StringKey("$PREFERENCES_NAMESPACE.automaticFetchInterval")
    val profileAdd = StringKey("$PREFERENCES_NAMESPACE.profileAdd")
    val profileEdit = StringKey("$PREFERENCES_NAMESPACE.profileEdit")
    val profileDelete = StringKey("$PREFERENCES_NAMESPACE.profileDelete")
    val profileDeleteConfirm = StringKey("$PREFERENCES_NAMESPACE.profileDeleteConfirm")
    val repositoryMapping = StringKey("$PREFERENCES_NAMESPACE.repositoryMapping")
    val repositoryMappingUnset = StringKey("$PREFERENCES_NAMESPACE.repositoryMappingUnset")
    val emailInvalid = StringKey("$PREFERENCES_NAMESPACE.emailInvalid")
    val customToolCommand = StringKey("$PREFERENCES_NAMESPACE.customToolCommand")
    val executableNotFound = StringKey("$PREFERENCES_NAMESPACE.executableNotFound")
    val tabWidth = StringKey("$PREFERENCES_NAMESPACE.tabWidth")
    val monospaceFont = StringKey("$PREFERENCES_NAMESPACE.monospaceFont")
    val monospaceFontSystem = StringKey("$PREFERENCES_NAMESPACE.monospaceFontSystem")
    val shortcutConflict = StringKey("$PREFERENCES_NAMESPACE.shortcutConflict")
    val shortcutReplaceConfirm = StringKey("$PREFERENCES_NAMESPACE.shortcutReplaceConfirm")
    val shortcutClear = StringKey("$PREFERENCES_NAMESPACE.shortcutClear")
    val shortcutApplyFailed = StringKey("$PREFERENCES_NAMESPACE.shortcutApplyFailed")
    val largeFileThreshold = StringKey("$PREFERENCES_NAMESPACE.largeFileThreshold")
    val commitPageSize = StringKey("$PREFERENCES_NAMESPACE.commitPageSize")
    val logLocation = StringKey("$PREFERENCES_NAMESPACE.logLocation")
    val openFolder = StringKey("$PREFERENCES_NAMESPACE.openFolder")
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
    val invalidValue: String get() = strings.text(PreferencesKeys.invalidValue)
    val defaultBranchName: String get() = strings.text(PreferencesKeys.defaultBranchName)
    val pullStrategy: String get() = strings.text(PreferencesKeys.pullStrategy)
    val pullStrategyMerge: String get() = strings.text(PreferencesKeys.pullStrategyMerge)
    val pullStrategyRebase: String get() = strings.text(PreferencesKeys.pullStrategyRebase)
    val automaticFetch: String get() = strings.text(PreferencesKeys.automaticFetch)
    val automaticFetchInterval: String get() = strings.text(PreferencesKeys.automaticFetchInterval)
    val profileAdd: String get() = strings.text(PreferencesKeys.profileAdd)
    val profileEdit: String get() = strings.text(PreferencesKeys.profileEdit)
    val profileDelete: String get() = strings.text(PreferencesKeys.profileDelete)
    val profileDeleteConfirm: String get() = strings.text(PreferencesKeys.profileDeleteConfirm)
    val repositoryMapping: String get() = strings.text(PreferencesKeys.repositoryMapping)
    val repositoryMappingUnset: String get() = strings.text(PreferencesKeys.repositoryMappingUnset)
    val emailInvalid: String get() = strings.text(PreferencesKeys.emailInvalid)
    val customToolCommand: String get() = strings.text(PreferencesKeys.customToolCommand)
    val executableNotFound: String get() = strings.text(PreferencesKeys.executableNotFound)
    val tabWidth: String get() = strings.text(PreferencesKeys.tabWidth)
    val monospaceFont: String get() = strings.text(PreferencesKeys.monospaceFont)
    val monospaceFontSystem: String get() = strings.text(PreferencesKeys.monospaceFontSystem)
    val shortcutConflict: String get() = strings.text(PreferencesKeys.shortcutConflict)
    val shortcutReplaceConfirm: String get() = strings.text(PreferencesKeys.shortcutReplaceConfirm)
    val shortcutClear: String get() = strings.text(PreferencesKeys.shortcutClear)
    val shortcutApplyFailed: String get() = strings.text(PreferencesKeys.shortcutApplyFailed)
    val largeFileThreshold: String get() = strings.text(PreferencesKeys.largeFileThreshold)
    val commitPageSize: String get() = strings.text(PreferencesKeys.commitPageSize)
    val logLocation: String get() = strings.text(PreferencesKeys.logLocation)
    val openFolder: String get() = strings.text(PreferencesKeys.openFolder)
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
            "화면·동작 취향과 Git·도구·고급 설정, 단축키, 열린 탭이 모두 기본값으로 돌아갑니다. " +
            "신원 프로필과 외부 도구 경로, 저장소의 git 설정은 그대로 둡니다. 되돌릴 수 없습니다.",
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
        PreferencesKeys.invalidValue to "값이 올바르지 않아 저장하지 않았습니다",
        PreferencesKeys.defaultBranchName to "기본 브랜치 이름",
        PreferencesKeys.pullStrategy to "pull 방식",
        PreferencesKeys.pullStrategyMerge to "병합(merge)",
        PreferencesKeys.pullStrategyRebase to "재배치(rebase)",
        PreferencesKeys.automaticFetch to "자동 fetch",
        PreferencesKeys.automaticFetchInterval to "fetch 주기(분)",
        PreferencesKeys.profileAdd to "프로필 추가",
        PreferencesKeys.profileEdit to "프로필 수정",
        PreferencesKeys.profileDelete to "프로필 삭제",
        PreferencesKeys.profileDeleteConfirm to "이 프로필을 지웁니다. 되돌릴 수 없습니다.",
        PreferencesKeys.repositoryMapping to "이 저장소에 쓸 프로필",
        PreferencesKeys.repositoryMappingUnset to "지정하지 않음",
        PreferencesKeys.emailInvalid to "이메일 형식이 올바르지 않습니다",
        PreferencesKeys.customToolCommand to "사용자 지정 명령",
        PreferencesKeys.executableNotFound to "실행 파일을 찾을 수 없습니다",
        PreferencesKeys.tabWidth to "탭 폭(칸)",
        PreferencesKeys.monospaceFont to "고정폭 서체",
        PreferencesKeys.monospaceFontSystem to "시스템 기본을 따름",
        PreferencesKeys.shortcutConflict to "다른 명령이 이미 쓰고 있는 단축키입니다",
        PreferencesKeys.shortcutReplaceConfirm to "이 단축키를 새 명령으로 옮깁니다",
        PreferencesKeys.shortcutClear to "단축키 해제",
        PreferencesKeys.shortcutApplyFailed to "단축키를 적용하지 못했습니다",
        PreferencesKeys.largeFileThreshold to "대용량 파일 임계치(바이트)",
        PreferencesKeys.commitPageSize to "이력을 한 번에 읽을 개수",
        PreferencesKeys.logLocation to "로그 위치",
        PreferencesKeys.openFolder to "폴더 열기",
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
            "Appearance and behaviour preferences, Git, tool and advanced settings, shortcuts and open " +
            "tabs all go back to their defaults. Identity profiles, external tool paths and repository " +
            "git config are left alone. This cannot be undone.",
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
        PreferencesKeys.invalidValue to "Not saved — the value is not valid",
        PreferencesKeys.defaultBranchName to "Default branch name",
        PreferencesKeys.pullStrategy to "Pull strategy",
        PreferencesKeys.pullStrategyMerge to "Merge",
        PreferencesKeys.pullStrategyRebase to "Rebase",
        PreferencesKeys.automaticFetch to "Automatic fetch",
        PreferencesKeys.automaticFetchInterval to "Fetch every (minutes)",
        PreferencesKeys.profileAdd to "Add profile",
        PreferencesKeys.profileEdit to "Edit profile",
        PreferencesKeys.profileDelete to "Delete profile",
        PreferencesKeys.profileDeleteConfirm to "This deletes the profile. It cannot be undone.",
        PreferencesKeys.repositoryMapping to "Profile for this repository",
        PreferencesKeys.repositoryMappingUnset to "Not assigned",
        PreferencesKeys.emailInvalid to "That is not a valid email address",
        PreferencesKeys.customToolCommand to "Custom command",
        PreferencesKeys.executableNotFound to "Could not find that executable",
        PreferencesKeys.tabWidth to "Tab width (columns)",
        PreferencesKeys.monospaceFont to "Monospace font",
        PreferencesKeys.monospaceFontSystem to "Follow system",
        PreferencesKeys.shortcutConflict to "Another command already uses this shortcut",
        PreferencesKeys.shortcutReplaceConfirm to "Move this shortcut to the new command",
        PreferencesKeys.shortcutClear to "Clear shortcut",
        PreferencesKeys.shortcutApplyFailed to "Could not apply the shortcut",
        PreferencesKeys.largeFileThreshold to "Large file threshold (bytes)",
        PreferencesKeys.commitPageSize to "Commits loaded per page",
        PreferencesKeys.logLocation to "Log location",
        PreferencesKeys.openFolder to "Open folder",
    ),
)
