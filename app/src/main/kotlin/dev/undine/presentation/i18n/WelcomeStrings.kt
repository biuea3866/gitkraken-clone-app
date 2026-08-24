package dev.undine.presentation.i18n

import java.util.Locale

private const val NAMESPACE = "welcome"

/** `welcome.*` 키 정의 — 첫 실행 화면(최근 저장소·로컬 열기·원격 클론)의 문구. */
object WelcomeKeys {
    val title = StringKey("$NAMESPACE.title")
    val recentTitle = StringKey("$NAMESPACE.recent.title")
    val recentEmpty = StringKey("$NAMESPACE.recent.empty")
    val recentEmptyDescription = StringKey("$NAMESPACE.recent.emptyDescription")
    val recentUnavailable = StringKey("$NAMESPACE.recent.unavailable")
    val recentRemove = StringKey("$NAMESPACE.recent.remove")
    val openTitle = StringKey("$NAMESPACE.open.title")
    val openAction = StringKey("$NAMESPACE.open.action")
    val cloneTitle = StringKey("$NAMESPACE.clone.title")
    val cloneUrlLabel = StringKey("$NAMESPACE.clone.urlLabel")
    val cloneTargetLabel = StringKey("$NAMESPACE.clone.targetLabel")
    val cloneStart = StringKey("$NAMESPACE.clone.start")
    val cloneCancel = StringKey("$NAMESPACE.clone.cancel")
    val cloneProgress = StringKey("$NAMESPACE.clone.progress")
    val errorNotFound = StringKey("$NAMESPACE.error.notFound")
    val errorNotARepository = StringKey("$NAMESPACE.error.notARepository")
    val errorPermissionDenied = StringKey("$NAMESPACE.error.permissionDenied")
    val errorBareRepository = StringKey("$NAMESPACE.error.bareRepository")

    /**
     * 사유를 특정할 수 없는 열기 실패. `InvalidRepositoryPath` 네 사유가 커버하지 못하는
     * `GitOperationFailed` 같은 실패까지 화면이 조용히 삼키지 않도록 둔다.
     */
    val errorOpenFailed = StringKey("$NAMESPACE.error.openFailed")
    val errorAuthentication = StringKey("$NAMESPACE.error.authentication")
    val errorTargetNotEmpty = StringKey("$NAMESPACE.error.targetNotEmpty")
    val errorCloneFailed = StringKey("$NAMESPACE.error.cloneFailed")
    val errorCleanupFailed = StringKey("$NAMESPACE.error.cleanupFailed")

    /** 번역 누락 검사가 도는 전체 키 목록. 키를 추가하면 여기에도 넣는다. */
    val all: List<StringKey> = listOf(
        title, recentTitle, recentEmpty, recentEmptyDescription, recentUnavailable, recentRemove,
        openTitle, openAction, cloneTitle, cloneUrlLabel, cloneTargetLabel, cloneStart, cloneCancel,
        cloneProgress, errorNotFound, errorNotARepository, errorPermissionDenied, errorBareRepository,
        errorOpenFailed, errorAuthentication, errorTargetNotEmpty, errorCloneFailed, errorCleanupFailed,
    )
}

/**
 * Welcome 문구 접근자. `strings.welcome.cloneStart` 로 읽는다.
 *
 * **[builtInTranslations] 등록은 하지 않는다** — 그 목록은 여러 티켓이 한 줄씩 고치면 충돌하는
 * 공용 파일이라 등록을 UND-26 이 일괄로 한다 (wave 3 결정 A3).
 *
 * 오류 문구에 원격 URL·자격증명을 넣지 않는다 — URL 에 토큰이 섞여 있을 수 있다
 * (`credential-handling` 2항). 인증 실패는 "무엇을 확인하라"만 말한다.
 */
@JvmInline
value class WelcomeStrings internal constructor(private val strings: Strings) {
    val title: String get() = strings.text(WelcomeKeys.title)
    val recentTitle: String get() = strings.text(WelcomeKeys.recentTitle)
    val recentEmpty: String get() = strings.text(WelcomeKeys.recentEmpty)
    val recentEmptyDescription: String get() = strings.text(WelcomeKeys.recentEmptyDescription)
    val recentUnavailable: String get() = strings.text(WelcomeKeys.recentUnavailable)
    val recentRemove: String get() = strings.text(WelcomeKeys.recentRemove)
    val openTitle: String get() = strings.text(WelcomeKeys.openTitle)
    val openAction: String get() = strings.text(WelcomeKeys.openAction)
    val cloneTitle: String get() = strings.text(WelcomeKeys.cloneTitle)
    val cloneUrlLabel: String get() = strings.text(WelcomeKeys.cloneUrlLabel)
    val cloneTargetLabel: String get() = strings.text(WelcomeKeys.cloneTargetLabel)
    val cloneStart: String get() = strings.text(WelcomeKeys.cloneStart)
    val cloneCancel: String get() = strings.text(WelcomeKeys.cloneCancel)
    val errorNotFound: String get() = strings.text(WelcomeKeys.errorNotFound)
    val errorNotARepository: String get() = strings.text(WelcomeKeys.errorNotARepository)
    val errorPermissionDenied: String get() = strings.text(WelcomeKeys.errorPermissionDenied)
    val errorBareRepository: String get() = strings.text(WelcomeKeys.errorBareRepository)
    val errorOpenFailed: String get() = strings.text(WelcomeKeys.errorOpenFailed)
    val errorAuthentication: String get() = strings.text(WelcomeKeys.errorAuthentication)
    val errorTargetNotEmpty: String get() = strings.text(WelcomeKeys.errorTargetNotEmpty)
    val errorCloneFailed: String get() = strings.text(WelcomeKeys.errorCloneFailed)

    /** @param percent 0~100 정수. 소수 자리 표기는 로케일 패턴이 정한다. */
    fun cloneProgress(phase: String, percent: Int): String =
        strings.text(WelcomeKeys.cloneProgress, phase, percent)

    /** @param path 앱이 지우지 못해 사용자가 직접 지워야 하는 **로컬** 경로. */
    fun cleanupFailed(path: String): String = strings.text(WelcomeKeys.errorCleanupFailed, path)
}

/** Welcome 문구 네임스페이스 진입점. */
val Strings.welcome: WelcomeStrings get() = WelcomeStrings(this)

internal val welcomeTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        WelcomeKeys.title to "저장소 열기",
        WelcomeKeys.recentTitle to "최근 저장소",
        WelcomeKeys.recentEmpty to "최근에 연 저장소가 없습니다",
        WelcomeKeys.recentEmptyDescription to "로컬 저장소를 열거나 원격 저장소를 클론해 시작하세요",
        WelcomeKeys.recentUnavailable to "찾을 수 없음",
        WelcomeKeys.recentRemove to "목록에서 제거",
        WelcomeKeys.openTitle to "로컬 저장소",
        WelcomeKeys.openAction to "디렉터리 선택",
        WelcomeKeys.cloneTitle to "원격 저장소 클론",
        WelcomeKeys.cloneUrlLabel to "원격 주소",
        WelcomeKeys.cloneTargetLabel to "저장할 디렉터리",
        WelcomeKeys.cloneStart to "클론",
        WelcomeKeys.cloneCancel to "클론 취소",
        WelcomeKeys.cloneProgress to "{0} {1}%",
        WelcomeKeys.errorNotFound to "경로를 찾을 수 없습니다. 위치가 바뀌었는지 확인하세요.",
        WelcomeKeys.errorNotARepository to "Git 저장소가 아닙니다. .git 이 있는 디렉터리를 고르세요.",
        WelcomeKeys.errorPermissionDenied to "읽을 권한이 없습니다. 디렉터리 접근 권한을 확인하세요.",
        WelcomeKeys.errorBareRepository to "베어 저장소는 열 수 없습니다. 워킹트리가 있는 저장소를 고르세요.",
        WelcomeKeys.errorOpenFailed to "저장소를 열지 못했습니다. 잠시 후 다시 시도하고, 반복되면 로그를 확인하세요.",
        WelcomeKeys.errorAuthentication to "인증에 실패했습니다. 키체인과 SSH 설정을 확인하세요.",
        WelcomeKeys.errorTargetNotEmpty to "대상 디렉터리가 비어 있지 않습니다. 빈 디렉터리를 고르세요.",
        WelcomeKeys.errorCloneFailed to "클론에 실패했습니다. 주소와 네트워크 상태를 확인하세요.",
        WelcomeKeys.errorCleanupFailed to "받다 만 디렉터리를 지우지 못했습니다. 직접 지워 주세요: {0}",
    ),
    Locale.ENGLISH to mapOf(
        WelcomeKeys.title to "Open a repository",
        WelcomeKeys.recentTitle to "Recent repositories",
        WelcomeKeys.recentEmpty to "No recent repositories",
        WelcomeKeys.recentEmptyDescription to "Open a local repository or clone a remote one to get started",
        WelcomeKeys.recentUnavailable to "Not found",
        WelcomeKeys.recentRemove to "Remove from list",
        WelcomeKeys.openTitle to "Local repository",
        WelcomeKeys.openAction to "Choose directory",
        WelcomeKeys.cloneTitle to "Clone a remote repository",
        WelcomeKeys.cloneUrlLabel to "Remote URL",
        WelcomeKeys.cloneTargetLabel to "Target directory",
        WelcomeKeys.cloneStart to "Clone",
        WelcomeKeys.cloneCancel to "Cancel clone",
        WelcomeKeys.cloneProgress to "{0} {1}%",
        WelcomeKeys.errorNotFound to "That path no longer exists. Check whether it moved.",
        WelcomeKeys.errorNotARepository to "That directory is not a Git repository. Pick one that contains .git.",
        WelcomeKeys.errorPermissionDenied to "No permission to read that path. Check directory access rights.",
        WelcomeKeys.errorBareRepository to "Bare repositories have no working tree and cannot be opened.",
        WelcomeKeys.errorOpenFailed to "Could not open that repository. Try again, and check the logs if it persists.",
        WelcomeKeys.errorAuthentication to "Authentication failed. Check your keychain and SSH configuration.",
        WelcomeKeys.errorTargetNotEmpty to "The target directory is not empty. Pick an empty directory.",
        WelcomeKeys.errorCloneFailed to "Clone failed. Check the URL and your network connection.",
        WelcomeKeys.errorCleanupFailed to "Could not remove the partial clone. Please delete it manually: {0}",
    ),
)
