package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `update.*` 네임스페이스 — 자동 업데이트 안내 배너와 일반 탭의 확인 설정 문구.
 *
 * 구조는 [CommonStrings] 가 정본이다: [UPDATE_NAMESPACE] 로 키를 만들고, 로케일별 번역을 채우고,
 * `Strings.update` 확장 프로퍼티로 노출한 뒤 [builtInTranslationSources] 에 한 줄만 더한다.
 *
 * 문구가 `preferences.*` 로 가지 않는 이유: 이 네임스페이스는 **설정 화면 밖의 전역 배너**도 함께
 * 쓴다. `PreferencesStrings` 는 탭 티켓이 수정하지 않기로 한 파일이기도 하다.
 */
internal const val UPDATE_NAMESPACE: String = "update"

/** 자동 업데이트의 `update.*` 키. */
object UpdateKeys {
    val newVersion = StringKey("$UPDATE_NAMESPACE.newVersion")
    val releaseNotes = StringKey("$UPDATE_NAMESPACE.releaseNotes")
    val releaseNotesEmpty = StringKey("$UPDATE_NAMESPACE.releaseNotesEmpty")
    val install = StringKey("$UPDATE_NAMESPACE.install")
    val later = StringKey("$UPDATE_NAMESPACE.later")
    val installing = StringKey("$UPDATE_NAMESPACE.installing")

    /** 진행 중인 작업 때문에 설치를 미룬다는 안내. 사유는 화면이 뒤에 그대로 잇는다 (결정 D11). */
    val installDeferred = StringKey("$UPDATE_NAMESPACE.installDeferred")

    val installOpened = StringKey("$UPDATE_NAMESPACE.installOpened")
    val installOpenFailed = StringKey("$UPDATE_NAMESPACE.installOpenFailed")
    val checksumMismatch = StringKey("$UPDATE_NAMESPACE.checksumMismatch")
    val downloadFailed = StringKey("$UPDATE_NAMESPACE.downloadFailed")
    val automaticCheck = StringKey("$UPDATE_NAMESPACE.automaticCheck")
    val checkInterval = StringKey("$UPDATE_NAMESPACE.checkInterval")
}

/** 자동 업데이트 문구 접근자. */
@JvmInline
value class UpdateStrings internal constructor(private val strings: Strings) {

    /** 새 버전 안내. `{0}` 은 릴리즈 버전이다. */
    fun newVersion(version: String): String = strings.text(UpdateKeys.newVersion, version)

    val releaseNotes: String get() = strings.text(UpdateKeys.releaseNotes)
    val releaseNotesEmpty: String get() = strings.text(UpdateKeys.releaseNotesEmpty)
    val install: String get() = strings.text(UpdateKeys.install)
    val later: String get() = strings.text(UpdateKeys.later)
    val installing: String get() = strings.text(UpdateKeys.installing)
    val installDeferred: String get() = strings.text(UpdateKeys.installDeferred)

    /** `{0}` 은 남겨 둔 설치 파일 경로다 — 설치 도중 지우면 설치가 깨진다 (결정 D15). */
    fun installOpened(path: String): String = strings.text(UpdateKeys.installOpened, path)

    /** `{0}` 은 파일 경로, `{1}` 은 실패 사유다. 파일은 남으므로 직접 실행할 수 있다 (결정 D18). */
    fun installOpenFailed(path: String, reason: String): String =
        strings.text(UpdateKeys.installOpenFailed, path, reason)

    val checksumMismatch: String get() = strings.text(UpdateKeys.checksumMismatch)
    val downloadFailed: String get() = strings.text(UpdateKeys.downloadFailed)
    val automaticCheck: String get() = strings.text(UpdateKeys.automaticCheck)
    val checkInterval: String get() = strings.text(UpdateKeys.checkInterval)
}

/** 자동 업데이트 문구 네임스페이스 진입점. */
val Strings.update: UpdateStrings get() = UpdateStrings(this)

internal val updateTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        UpdateKeys.newVersion to "새 버전 {0} 이(가) 있습니다",
        UpdateKeys.releaseNotes to "릴리즈 노트",
        UpdateKeys.releaseNotesEmpty to "이 릴리즈에는 노트가 없습니다.",
        UpdateKeys.install to "지금 설치",
        UpdateKeys.later to "나중에",
        UpdateKeys.installing to "내려받아 검증하는 중…",
        UpdateKeys.installDeferred to "진행 중인 작업이 끝난 뒤 설치할 수 있습니다:",
        UpdateKeys.installOpened to
            "설치 관리자에 넘겼습니다. 설치가 끝날 때까지 받은 파일을 지우지 않습니다: {0}",
        UpdateKeys.installOpenFailed to
            "설치 파일을 열지 못했습니다. 직접 실행하세요: {0} ({1})",
        UpdateKeys.checksumMismatch to
            "받은 파일의 무결성 검증에 실패해 설치하지 않았습니다. 기존 설치본은 그대로입니다.",
        UpdateKeys.downloadFailed to "업데이트 파일을 받지 못했습니다. 다음 확인에서 다시 시도합니다.",
        UpdateKeys.automaticCheck to "자동 업데이트 확인",
        UpdateKeys.checkInterval to "확인 주기(시간)",
    ),
    Locale.ENGLISH to mapOf(
        UpdateKeys.newVersion to "Version {0} is available",
        UpdateKeys.releaseNotes to "Release notes",
        UpdateKeys.releaseNotesEmpty to "This release has no notes.",
        UpdateKeys.install to "Install now",
        UpdateKeys.later to "Later",
        UpdateKeys.installing to "Downloading and verifying…",
        UpdateKeys.installDeferred to "You can install once the running operation finishes:",
        UpdateKeys.installOpened to
            "Handed the installer to your system. The downloaded file is kept until installation finishes: {0}",
        UpdateKeys.installOpenFailed to
            "Couldn''t open the installer. Run it yourself: {0} ({1})",
        UpdateKeys.checksumMismatch to
            "Integrity check failed, so nothing was installed. Your install is untouched.",
        UpdateKeys.downloadFailed to "Couldn''t download the update. It will be retried at the next check.",
        UpdateKeys.automaticCheck to "Check for updates automatically",
        UpdateKeys.checkInterval to "Check interval (hours)",
    ),
)
