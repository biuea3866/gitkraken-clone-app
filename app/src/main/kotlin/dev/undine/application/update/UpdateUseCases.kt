package dev.undine.application.update

import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateGateway
import dev.undine.domain.update.UpdateInstallResult
import java.nio.file.Path

/**
 * 최신 릴리즈를 확인한다.
 *
 * **결과를 가공하지 않는다.** 확인 실패를 "업데이트 없음" 으로 접으면 몇 달째 확인이 실패하는 앱이
 * 계속 "최신입니다" 를 보여 준다 (결정 D6). 화면 표현을 조용하게 하는 것은 presentation 의 일이고,
 * 상태를 구분해 올리는 것은 여기까지의 일이다.
 */
class CheckUpdateUseCase(private val updateGateway: UpdateGateway) {

    suspend fun execute(): UpdateCheckResult = updateGateway.checkForUpdate()
}

/**
 * 사용자가 동의한 업데이트를 받아 검증하고 OS 기본 동작으로 연다.
 *
 * **순서가 이 UseCase 의 책임이다** — 다운로드 → 검증 → 열기. 검증을 통과하지 못한 결과에서는
 * [UpdateGateway.openInstaller] 를 부르지 않는다: 검증되지 않은 파일을 여는 순간 무결성 검증은
 * 아무것도 막지 못한 것이 된다.
 *
 * 이 UseCase 는 **동의를 판단하지 않는다.** 부르는 것 자체가 동의의 결과이고, 진행 중인 Git 작업
 * 판정도 화면이 본다 (결정 D11) — 그 판정은 `AppNavigationState` 라는 presentation 상태다.
 */
class InstallUpdateUseCase(private val updateGateway: UpdateGateway) {

    suspend fun execute(release: AvailableRelease): UpdateInstallResult =
        when (val downloaded = updateGateway.downloadAndVerify(release)) {
            is UpdateDownloadResult.Verified -> open(downloaded.installer)
            is UpdateDownloadResult.ChecksumMismatch -> UpdateInstallResult.ChecksumMismatch(downloaded.assetName)
            is UpdateDownloadResult.DownloadFailed ->
                UpdateInstallResult.DownloadFailed(downloaded.kind, downloaded.detail)
        }

    private suspend fun open(installer: Path): UpdateInstallResult =
        when (val launched = updateGateway.openInstaller(installer)) {
            is InstallerLaunchResult.Opened -> UpdateInstallResult.Opened(launched.installer)
            is InstallerLaunchResult.OpenFailed ->
                UpdateInstallResult.OpenFailed(launched.installer, launched.reason)
        }
}

/**
 * 업데이트 UseCase 묶음. 배선이 화면에 **한 의존성으로** 넘긴다 —
 * [dev.undine.application.diagnostics.DiagnosticsUseCases] 와 같은 이유다.
 */
data class UpdateUseCases(
    val check: CheckUpdateUseCase,
    val install: InstallUpdateUseCase,
)
