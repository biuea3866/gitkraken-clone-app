package dev.undine.presentation.update

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.di.AppComponent
import dev.undine.domain.Settings
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateGateway
import dev.undine.presentation.AppErrorState
import dev.undine.presentation.AppRoot
import dev.undine.presentation.AppWiring
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/** 설정 파일을 실제로 읽어 화면까지 오는 경로라 기본 1초로는 모자란다 (`AppAppliedSettingsSpec` 과 같다). */
private const val WAIT_MILLIS = 30_000L

/** 확인이 돌지 않는 것을 확인하려면 "아직 안 돈 것" 과 구분할 시간이 필요하다. */
private const val SETTLE_ROUNDS = 20

private val CHECKS_ON = UpdateCheckSettings(enabled = true, intervalHours = 24)
private val CHECKS_OFF = UpdateCheckSettings(enabled = false, intervalHours = 24)

private val RELEASE = AvailableRelease(
    version = AppVersion(9, 9, 9),
    releaseNotes = "조립이 실제로 확인을 돌렸다",
    assetName = "undine-9.9.9-linux.deb",
    assetUrl = "https://x.invalid/deb",
    checksumsUrl = "https://x.invalid/checksums",
)

/** 확인 횟수만 세는 Gateway. **실제 github.com 을 부르지 않는다** (결정 D3). */
private class CountingUpdateGateway : UpdateGateway {

    val checks = AtomicInteger(0)

    override suspend fun checkForUpdate(): UpdateCheckResult {
        checks.incrementAndGet()
        return UpdateCheckResult.UpdateAvailable(RELEASE)
    }

    override suspend fun downloadAndVerify(release: AvailableRelease): UpdateDownloadResult =
        UpdateDownloadResult.Verified(Paths.get("update", release.assetName))

    override suspend fun openInstaller(installer: Path): InstallerLaunchResult =
        InstallerLaunchResult.Opened(installer)
}

/**
 * 조립이 자동 업데이트를 **실제로 켜는지** 본다.
 *
 * 홀더(`UpdateNoticeState`)와 배너를 따로 부르는 테스트는 그 둘이 맞다는 것만 말한다 — 앱이
 * 설정을 읽어 확인을 걸고 그 결과를 전역 배너로 그리는지는 말하지 않는다. **배선은 조용히 끊기고,
 * 끊긴 것은 화면을 열어 봐야만 안다** (결정 D25). 여기서는 `AppRoot` 를 실제 `AppComponent` 로
 * 띄우고, 네트워크만 대역으로 바꿔 확인한다.
 */
@OptIn(ExperimentalTestApi::class)
class AppUpdateNoticeAssemblySpec : FunSpec({

    test("설정이 도착하면 앱 전역에서 확인이 한 번 돌고 새 버전 안내가 조립된다") {
        val settingsFile = seedSettings { it.copy(updateCheck = CHECKS_ON) }
        val gateway = CountingUpdateGateway()

        runComposeUiTest {
            startApp(settingsFile, gateway)

            waitUntil(timeoutMillis = WAIT_MILLIS) { gateway.checks.get() >= 1 }
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(UpdateTags.RELEASE_NOTES).assertExists()
            onNodeWithTag(UpdateTags.INSTALL).assertExists()
        }
    }

    test("확인을 꺼 두면 앱 전역 확인이 아예 돌지 않는다 — 요청만 참는 것이 아니다") {
        val settingsFile = seedSettings { it.copy(updateCheck = CHECKS_OFF) }
        val gateway = CountingUpdateGateway()

        runComposeUiTest {
            startApp(settingsFile, gateway)
            repeat(SETTLE_ROUNDS) { waitForIdle() }

            gateway.checks.get() shouldBe 0
            onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().shouldBeEmpty()
        }
    }

    test("동의를 누르기 전에는 조립된 앱이 아무것도 내려받지 않는다") {
        val settingsFile = seedSettings { it.copy(updateCheck = CHECKS_ON) }
        val gateway = CountingUpdateGateway()

        runComposeUiTest {
            startApp(settingsFile, gateway)
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().isNotEmpty()
            }

            // 확인은 자동이지만 설치는 사용자가 누른 뒤에만 일어난다 (결정 D12) — 결과 문구가 아직 없다.
            onAllNodesWithTag(UpdateTags.RESULT).fetchSemanticsNodes().shouldBeEmpty()
        }
    }
})

/** 앱을 띄우기 전에 설정 파일에 값을 심는다 — 배선이 **파일에서** 읽는지 보려면 파일에 있어야 한다. */
private fun TestConfiguration.seedSettings(change: (Settings) -> Settings): Path {
    val settingsFile = File(tempdir(), "settings.json").toPath()
    runBlocking { AppComponent(settingsFile, settingsFile.parent).updatePreferences.execute(change) }
    return settingsFile
}

/** 조립을 띄우고 조립이 끝날 때까지 기다린다 (`AppAppliedSettingsSpec` 과 같은 통로). */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.startApp(settingsFile: Path, updateGateway: UpdateGateway) {
    val component = AppComponent(
        settingsFile = settingsFile,
        appDirectory = settingsFile.parent,
        updateGateway = updateGateway,
    )
    var assembled: AppWiring? = null
    setContent {
        AppRoot(component = component, errors = AppErrorState(), onAssembled = { assembled = it })
    }
    waitUntil(timeoutMillis = WAIT_MILLIS) { assembled != null }
    assembled.shouldNotBeNull()
}
