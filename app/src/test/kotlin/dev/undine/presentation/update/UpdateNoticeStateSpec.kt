package dev.undine.presentation.update

import dev.undine.application.update.CheckUpdateUseCase
import dev.undine.application.update.InstallUpdateUseCase
import dev.undine.application.update.UpdateUseCases
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateFailureKind
import dev.undine.domain.update.UpdateGateway
import dev.undine.domain.update.UpdateInstallResult
import dev.undine.testsupport.inlineTestScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

private val RELEASE = AvailableRelease(
    version = AppVersion(2, 0, 0),
    releaseNotes = "노트",
    assetName = "undine-2.0.0-linux.deb",
    assetUrl = "https://x.invalid/deb",
    checksumsUrl = "https://x.invalid/checksums",
)

private val INSTALLER: Path = Paths.get("update", RELEASE.assetName)

private const val BLOCKED_REASON = "리베이스를 적용하는 중입니다"

/**
 * 확인 결과를 **순서대로** 돌려주고 마지막 값을 반복하는 Gateway.
 *
 * 한 홀더에서 "새 버전 → 최신" 같은 전이를 봐야 안내가 거둬지는지 알 수 있다 — 인스턴스를 새로
 * 만들어 비교하면 첫 확인이 그랬던 것과 구분되지 않는다.
 */
private class ScriptedUpdateGateway(
    private val checkResults: List<UpdateCheckResult> = listOf(UpdateCheckResult.UpToDate),
    private val downloadResult: UpdateDownloadResult = UpdateDownloadResult.Verified(INSTALLER),
    private val launchResult: InstallerLaunchResult = InstallerLaunchResult.Opened(INSTALLER),
) : UpdateGateway {

    val calls = mutableListOf<String>()

    private var checks = 0

    override suspend fun checkForUpdate(): UpdateCheckResult {
        calls += "check"
        return checkResults[minOf(checks++, checkResults.lastIndex)]
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

private fun useCasesOf(gateway: UpdateGateway) = UpdateUseCases(
    check = CheckUpdateUseCase(gateway),
    install = InstallUpdateUseCase(gateway),
)

/**
 * 대기를 **테스트가 붙잡는** 시계.
 *
 * 실제 `delay` 를 쓰면 24시간을 기다리거나 짧은 주기로 바꿔 타이밍에 기대게 된다. 여기서는 다음
 * 주기를 열어 줄 때까지 매달아 두고, [releaseOnce] 로 한 주기씩 넘긴다 — 확인 횟수가 결정적이다.
 */
private class ManualClock {

    val requestedIntervals = mutableListOf<Long>()

    private val gate = Channel<Unit>(Channel.RENDEZVOUS)

    val await: suspend (Long) -> Unit = { millis ->
        requestedIntervals += millis
        gate.receive()
    }

    suspend fun releaseOnce() {
        gate.send(Unit)
    }
}

private fun stateOf(
    gateway: UpdateGateway,
    scope: CoroutineScope,
    clock: ManualClock = ManualClock(),
    blockedReason: () -> String? = { null },
) = UpdateNoticeState(
    scope = scope,
    updates = useCasesOf(gateway),
    installBlockedReason = blockedReason,
    awaitNextCheck = clock.await,
)

/**
 * 시작 확인·주기 확인·동의 게이트.
 *
 * **네트워크를 타지 않는다** (결정 D3) — Gateway 결과를 정해 두고 홀더의 판단만 본다.
 */
class UpdateNoticeStateSpec : FunSpec({

    test("시작 직후 한 번 확인하고 주기마다 다시 확인한다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpToDate))
        val clock = ManualClock()
        val scope = inlineTestScope()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings(enabled = true, intervalHours = 24)) }
            gateway.calls shouldContainExactly listOf("check")
            clock.releaseOnce()
            gateway.calls shouldContainExactly listOf("check", "check")
            clock.releaseOnce()
            gateway.calls shouldContainExactly listOf("check", "check", "check")
            checking.cancelAndJoin()
        }

        // 주기는 설정 값 그대로다 — 24시간.
        clock.requestedIntervals.first() shouldBe 24L * 60 * 60 * 1000
    }

    test("확인을 끄면 시작 직후에도 이후에도 확인하지 않는다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val state = stateOf(gateway, scope)

        runBlocking { state.runChecks(UpdateCheckSettings(enabled = false, intervalHours = 24)) }

        gateway.calls.shouldBeEmpty()
        state.availableRelease.shouldBeNull()
    }

    test("뜻 없는 주기가 저장돼 있으면 기본 주기로 확인한다") {
        val settings = UpdateCheckSettings(enabled = true, intervalHours = 0)

        intervalMillisOf(settings) shouldBe UpdateCheckSettings.DEFAULT.intervalHours * 60L * 60 * 1000
    }

    test("새 버전을 찾으면 안내할 릴리즈가 생긴다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.availableRelease shouldBe RELEASE
            checking.cancelAndJoin()
        }
    }

    test("확인 실패는 화면에 오류를 만들지 않지만 최신으로 접히지도 않는다") {
        val failure = UpdateCheckResult.CheckFailed(UpdateFailureKind.NETWORK, "오프라인")
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE), failure))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            clock.releaseOnce()
            // 실패했다고 안내를 거두지 않는다 — 새 버전이 사라진 것이 아니다.
            state.availableRelease shouldBe RELEASE
            state.lastCheckResult shouldBe failure
            checking.cancelAndJoin()
        }
    }

    test("릴리즈 없음(404)과 최신은 다른 상태로 남는다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.NoRelease))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.lastCheckResult shouldBe UpdateCheckResult.NoRelease
            state.availableRelease.shouldBeNull()
            checking.cancelAndJoin()
        }
    }

    test("최신임이 확인되면 안내를 거둔다") {
        val gateway = ScriptedUpdateGateway(
            listOf(UpdateCheckResult.UpdateAvailable(RELEASE), UpdateCheckResult.UpToDate),
        )
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            clock.releaseOnce()
            state.availableRelease.shouldBeNull()
            checking.cancelAndJoin()
        }
    }

    test("동의하지 않으면 다운로드도 열기도 일어나지 않는다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            gateway.calls shouldContainExactly listOf("check")
            checking.cancelAndJoin()
        }
    }

    test("진행 중인 작업이 있으면 UseCase 를 부르지 않고 사유를 남긴다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock, blockedReason = { BLOCKED_REASON })

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.install()
            checking.cancelAndJoin()
        }

        gateway.calls shouldContainExactly listOf("check")
        state.deferredReason shouldBe BLOCKED_REASON
        state.installResult.shouldBeNull()
        // 취소가 아니라 보류다 — 안내는 그대로 남아 작업이 끝난 뒤 다시 누를 수 있다.
        state.availableRelease shouldBe RELEASE
    }

    test("막힌 사유가 풀리면 같은 안내에서 설치가 진행된다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val clock = ManualClock()
        var blocked: String? = BLOCKED_REASON
        val state = stateOf(gateway, scope, clock, blockedReason = { blocked })

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.install()
            blocked = null
            state.install()
            checking.cancelAndJoin()
        }

        gateway.calls shouldContainExactly listOf("check", "download", "open")
        state.installResult shouldBe UpdateInstallResult.Opened(INSTALLER)
        state.deferredReason.shouldBeNull()
    }

    test("검증에 실패하면 열기 없이 그 사실이 결과로 남는다") {
        val gateway = ScriptedUpdateGateway(
            checkResults = listOf(UpdateCheckResult.UpdateAvailable(RELEASE)),
            downloadResult = UpdateDownloadResult.ChecksumMismatch(RELEASE.assetName),
        )
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.install()
            checking.cancelAndJoin()
        }

        gateway.calls shouldContainExactly listOf("check", "download")
        state.installResult shouldBe UpdateInstallResult.ChecksumMismatch(RELEASE.assetName)
    }

    test("안내를 접으면 배너가 사라지고 결과도 함께 지워진다") {
        val gateway = ScriptedUpdateGateway(listOf(UpdateCheckResult.UpdateAvailable(RELEASE)))
        val scope = inlineTestScope()
        val clock = ManualClock()
        val state = stateOf(gateway, scope, clock)

        runBlocking {
            val checking = scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }
            state.install()
            state.dismiss()
            checking.cancelAndJoin()
        }

        state.availableRelease.shouldBeNull()
        state.installResult.shouldBeNull()
        state.deferredReason.shouldBeNull()
    }
})
