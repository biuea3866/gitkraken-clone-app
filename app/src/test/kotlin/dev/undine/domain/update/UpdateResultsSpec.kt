package dev.undine.domain.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Paths

private val RELEASE = AvailableRelease(
    version = AppVersion(2, 0, 0),
    releaseNotes = "그래프 열 폭 고정",
    assetName = "undine-2.0.0-macos.dmg",
    assetUrl = "https://example.invalid/undine-2.0.0-macos.dmg",
    checksumsUrl = "https://example.invalid/checksums.txt",
)

/**
 * 업데이트 상태가 **서로 다른 값**인지 본다.
 *
 * 이 스펙이 지키는 것은 타입이 아니라 결정 D6 이다 — "확인 실패" 와 "업데이트 없음" 이 같은 값이
 * 되면, 몇 달째 확인이 실패하는 앱이 계속 "최신입니다" 를 보여 준다. 릴리즈가 0건인 저장소(404)도
 * 최신인 것과 다른 사실이라 따로 둔다.
 */
class UpdateResultsSpec : FunSpec({

    test("새 버전·업데이트 없음·릴리즈 없음·확인 실패는 서로 다른 값이다") {
        val states: List<UpdateCheckResult> = listOf(
            UpdateCheckResult.UpdateAvailable(RELEASE),
            UpdateCheckResult.UpToDate,
            UpdateCheckResult.NoRelease,
            UpdateCheckResult.CheckFailed(UpdateFailureKind.NETWORK, "오프라인"),
        )

        states.distinct() shouldHaveSize states.size
        UpdateCheckResult.NoRelease shouldNotBe UpdateCheckResult.UpToDate
    }

    test("네트워크 실패와 계약 실패는 같은 값이 아니다") {
        val network = UpdateCheckResult.CheckFailed(UpdateFailureKind.NETWORK, "같은 사유 문구")
        val contract = UpdateCheckResult.CheckFailed(UpdateFailureKind.CONTRACT, "같은 사유 문구")

        network shouldNotBe contract
    }

    test("검증을 통과하지 못한 결과에서는 설치 파일 경로가 나오지 않는다") {
        val mismatch: UpdateDownloadResult = UpdateDownloadResult.ChecksumMismatch(RELEASE.assetName)
        val failed: UpdateDownloadResult = UpdateDownloadResult.DownloadFailed(UpdateFailureKind.NETWORK, "끊김")

        (mismatch as? UpdateDownloadResult.Verified)?.installer.shouldBeNull()
        (failed as? UpdateDownloadResult.Verified)?.installer.shouldBeNull()
    }

    test("열기 성공·실패 모두 파일 경로를 들고 있다 — 어느 쪽도 파일을 잃지 않는다") {
        val installer = Paths.get("update", RELEASE.assetName)

        InstallerLaunchResult.Opened(installer).installer shouldBe installer
        InstallerLaunchResult.OpenFailed(installer, "데스크톱 연동 없음").installer shouldBe installer
    }
})
