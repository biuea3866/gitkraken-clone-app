package dev.undine.domain.update

import java.nio.file.Path

/**
 * 확인·다운로드가 실패한 **종류**.
 *
 * 둘을 나누는 이유는 사용자가 아니라 **우리가** 할 일이 다르기 때문이다 — 네트워크는 다음 주기에
 * 그냥 다시 시도하면 되고, 계약 위반은 발행 쪽(`packaging/RELEASE-CONTRACT.md`)이 어긋났다는 신호라
 * 재시도로 낫지 않는다. 화면에는 어느 쪽도 오류로 띄우지 않지만(결정 D6), 로그에서는 갈라야 한다.
 */
enum class UpdateFailureKind {
    NETWORK,
    CONTRACT,
}

/**
 * 릴리즈에서 찾은 **이 OS 용** 설치 파일과 그 릴리즈의 안내.
 *
 * 자산 이름은 응답에서 고른 것이 아니라 **계약이 정한 이름으로 찾은 것**이다 — 비슷한 이름을
 * 추측해 고르면 잘못된 파일이 체크섬 대조까지 통과한다.
 */
data class AvailableRelease(
    val version: AppVersion,
    val releaseNotes: String,
    val assetName: String,
    val assetUrl: String,
    val checksumsUrl: String,
)

/**
 * 릴리즈 확인 결과.
 *
 * **네 상태를 하나로 접지 않는다** (결정 D6). 특히 [CheckFailed] 를 [UpToDate] 로 접으면 몇 달째
 * 확인이 실패하는 앱이 계속 "최신입니다" 를 보여 준다. [NoRelease] 도 따로 둔다 — 아직 발행된
 * 릴리즈가 없는 것은 최신인 것과 다른 사실이고, 지금 이 저장소가 바로 그 상태다 (결정 D21).
 */
sealed interface UpdateCheckResult {

    /** 지금 버전보다 높은 릴리즈가 있다. */
    data class UpdateAvailable(val release: AvailableRelease) : UpdateCheckResult

    /** 최신 릴리즈가 지금 버전 이하다. */
    data object UpToDate : UpdateCheckResult

    /** 저장소에 릴리즈가 하나도 없다(404). */
    data object NoRelease : UpdateCheckResult

    /** 확인 자체를 하지 못했다. [detail] 은 로그용이며 화면에 띄우지 않는다. */
    data class CheckFailed(val kind: UpdateFailureKind, val detail: String) : UpdateCheckResult
}

/**
 * 다운로드 + 체크섬 검증 결과.
 *
 * 검증을 통과한 파일만 열 수 있다 — [Verified] 가 아닌 결과에서 설치 파일 경로가 나오지 않는 이유다.
 */
sealed interface UpdateDownloadResult {

    /** 체크섬이 맞았다. [installer] 는 앱 데이터 디렉터리 아래에 남아 있다. */
    data class Verified(val installer: Path) : UpdateDownloadResult

    /**
     * 체크섬이 맞지 않아 설치하지 않는다. 받다 만 파일 하나를 치우려 시도하지만 **지웠다고 단정하지
     * 않는다** — 지우지 못할 수 있고, 그때 "지웠다" 고 말하면 화면이 거짓말한다. 확실한 것은
     * 설치하지 않았다는 것과 기존 설치본이 그대로라는 것이다.
     */
    data class ChecksumMismatch(val assetName: String) : UpdateDownloadResult

    /** 받지 못했다. 받다 만 파일을 치우려 시도하고, 기존 설치본은 그대로다. */
    data class DownloadFailed(val kind: UpdateFailureKind, val detail: String) : UpdateDownloadResult
}

/** OS 기본 동작으로 설치 파일을 연 결과. 어느 쪽이든 **파일은 남는다** (결정 D18). */
sealed interface InstallerLaunchResult {

    data class Opened(val installer: Path) : InstallerLaunchResult

    /** 열지 못했다. 사용자가 직접 실행할 수 있게 [installer] 위치를 그대로 올린다. */
    data class OpenFailed(val installer: Path, val reason: String) : InstallerLaunchResult
}

/**
 * 사용자가 동의한 뒤의 설치 결과.
 *
 * 열기 실패([OpenFailed])와 검증 실패([ChecksumMismatch])를 같은 처리로 접지 않는다 — 앞은 파일을
 * 남기고 위치를 알려야 하고, 뒤는 파일을 지운 뒤 아무것도 열지 않는다 (결정 D5·D18).
 */
sealed interface UpdateInstallResult {

    data class Opened(val installer: Path) : UpdateInstallResult

    data class OpenFailed(val installer: Path, val reason: String) : UpdateInstallResult

    data class ChecksumMismatch(val assetName: String) : UpdateInstallResult

    data class DownloadFailed(val kind: UpdateFailureKind, val detail: String) : UpdateInstallResult
}
