package dev.undine.application.update

import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateFailureKind
import dev.undine.domain.update.UpdateGateway
import dev.undine.domain.update.UpdateInstallResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.nio.file.Paths

private val RELEASE = AvailableRelease(
    version = AppVersion(2, 0, 0),
    releaseNotes = "노트",
    assetName = "undine-2.0.0-linux.deb",
    assetUrl = "https://example.invalid/undine-2.0.0-linux.deb",
    checksumsUrl = "https://example.invalid/checksums.txt",
)

private val INSTALLER: Path = Paths.get("update", RELEASE.assetName)

/**
 * 결과를 정해 두고 **호출 순서**를 남기는 Gateway.
 *
 * 순서를 남기는 이유는 결과 비교로 잡히지 않는 회귀가 있기 때문이다 — 검증에 실패했는데도 여는
 * 경로는 결과 타입만 봐서는 드러나지 않는다.
 */
private class RecordingUpdateGateway(
    private val checkResult: UpdateCheckResult = UpdateCheckResult.UpToDate,
    private val downloadResult: UpdateDownloadResult = UpdateDownloadResult.Verified(INSTALLER),
    private val launchResult: InstallerLaunchResult = InstallerLaunchResult.Opened(INSTALLER),
) : UpdateGateway {

    val calls = mutableListOf<String>()

    override suspend fun checkForUpdate(): UpdateCheckResult {
        calls += "check"
        return checkResult
    }

    override suspend fun downloadAndVerify(release: AvailableRelease): UpdateDownloadResult {
        calls += "download"
        return downloadResult
    }

    override suspend fun openInstaller(installer: Path): InstallerLaunchResult {
        calls += "open"
        return launchResult
    }
}

/**
 * 확인·설치 UseCase. **실제 네트워크를 타지 않는다** (결정 D3) — Gateway 결과를 정해 두고 전달과
 * 순서만 본다.
 */
class UpdateUseCasesSpec : BehaviorSpec({

    given("최신 릴리즈 조회") {

        `when`("Gateway 가 새 버전을 돌려주면") {
            then("그 결과가 가공 없이 그대로 올라온다") {
                val gateway = RecordingUpdateGateway(checkResult = UpdateCheckResult.UpdateAvailable(RELEASE))

                CheckUpdateUseCase(gateway).execute() shouldBe UpdateCheckResult.UpdateAvailable(RELEASE)
            }
        }

        `when`("확인이 네트워크 실패로 끝나면") {
            then("업데이트 없음으로 접히지 않고 실패 상태 그대로 올라온다") {
                val failure = UpdateCheckResult.CheckFailed(UpdateFailureKind.NETWORK, "오프라인")
                val gateway = RecordingUpdateGateway(checkResult = failure)

                CheckUpdateUseCase(gateway).execute() shouldBe failure
            }
        }

        `when`("저장소에 릴리즈가 하나도 없으면") {
            then("업데이트 없음이 아니라 릴리즈 없음이 올라온다") {
                val gateway = RecordingUpdateGateway(checkResult = UpdateCheckResult.NoRelease)

                CheckUpdateUseCase(gateway).execute() shouldBe UpdateCheckResult.NoRelease
            }
        }
    }

    given("사용자가 동의한 설치") {

        `when`("체크섬 검증을 통과하면") {
            then("다운로드·검증 다음에 열기가 이어진다") {
                val gateway = RecordingUpdateGateway()

                val result = InstallUpdateUseCase(gateway).execute(RELEASE)

                result shouldBe UpdateInstallResult.Opened(INSTALLER)
                gateway.calls shouldContainExactly listOf("download", "open")
            }
        }

        `when`("체크섬이 맞지 않으면") {
            then("열기를 부르지 않는다 — 검증되지 않은 파일은 열리지 않는다") {
                val gateway = RecordingUpdateGateway(
                    downloadResult = UpdateDownloadResult.ChecksumMismatch(RELEASE.assetName),
                )

                val result = InstallUpdateUseCase(gateway).execute(RELEASE)

                result shouldBe UpdateInstallResult.ChecksumMismatch(RELEASE.assetName)
                gateway.calls shouldContainExactly listOf("download")
            }
        }

        `when`("다운로드가 실패하면") {
            then("열기를 부르지 않고 실패 종류가 그대로 올라온다") {
                val gateway = RecordingUpdateGateway(
                    downloadResult = UpdateDownloadResult.DownloadFailed(UpdateFailureKind.CONTRACT, "계약 위반"),
                )

                val result = InstallUpdateUseCase(gateway).execute(RELEASE)

                result shouldBe UpdateInstallResult.DownloadFailed(UpdateFailureKind.CONTRACT, "계약 위반")
                gateway.calls shouldContainExactly listOf("download")
            }
        }

        `when`("검증은 통과했는데 열기만 실패하면") {
            then("파일 위치와 사유가 함께 올라온다 — 조용한 성공이 되지 않는다") {
                val gateway = RecordingUpdateGateway(
                    launchResult = InstallerLaunchResult.OpenFailed(INSTALLER, "데스크톱 연동 없음"),
                )

                val result = InstallUpdateUseCase(gateway).execute(RELEASE)

                result shouldBe UpdateInstallResult.OpenFailed(INSTALLER, "데스크톱 연동 없음")
                gateway.calls shouldContainExactly listOf("download", "open")
            }
        }

        `when`("아무도 설치를 부르지 않으면") {
            then("Gateway 의 어떤 경로도 실행되지 않는다 — 동의 없이 설치되지 않는다") {
                val gateway = RecordingUpdateGateway()

                InstallUpdateUseCase(gateway)

                gateway.calls shouldContainExactly emptyList()
            }
        }
    }
})
