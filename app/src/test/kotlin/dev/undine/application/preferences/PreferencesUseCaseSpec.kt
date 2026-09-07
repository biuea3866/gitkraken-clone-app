package dev.undine.application.preferences

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey
import dev.undine.domain.ThemeMode
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.signing.SigningFormat
import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.signing.SigningSettings
import dev.undine.domain.withDefaultPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.awt.event.KeyEvent
import java.io.IOException

private val PROFILE = IdentityProfile(
    name = "회사 계정",
    email = "work@example.com",
    signingKeyId = "ABCD1234",
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = "github.com",
)

private val TOOLS = ExternalToolSettings(
    diffTool = ExternalTool(executable = "meld", arguments = listOf("local", "remote")),
    mergeTool = null,
)

private val STORED = Settings.DEFAULTS.copy(
    recentRepositories = listOf(RepositoryPath("/tmp/repo")),
    theme = ThemeMode.DARK,
    identityProfiles = listOf(PROFILE),
    externalTools = TOOLS,
    language = "en-GB",
    confirmDestructiveActions = false,
    openTabs = listOf(RepositoryPath("/tmp/tab")),
    updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 72),
    shortcutOverrides = mapOf(
        "graph.refresh" to ShortcutBinding(
            keyCode = KeyEvent.VK_R,
            modifiers = setOf(ShortcutModifierKey.PRIMARY),
        ),
    ),
)

/** 뒤따르는 코루틴이 막히는 지점까지 진행할 기회. 막히지 않으면 그 사이에 gateway 에 닿는다. */
private const val YIELD_ROUNDS = 3

/**
 * 읽기·갱신이 임계구역에 **들어온 사실을 알리고** 밖에서 열어 줄 때까지 멈추는 가짜.
 *
 * 겹친 갱신을 재현하려면 "한 갱신이 gateway 안에 머무는 동안 다른 갱신이 시작한다" 를 만들어야
 * 하는데, 시간에 기대면 결정적이지 않다. 진입 신호와 통과 신호를 밖에서 쥔다.
 */
private class GatedSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    /** 임계구역 진입 신호. 호출마다 하나씩 쌓인다. */
    val entered = Channel<Unit>(Channel.UNLIMITED)

    /** 통과 허가. 하나 보낼 때마다 대기 중인 호출 하나가 진행한다. */
    val gate = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun load(): Settings {
        entered.send(Unit)
        gate.receive()
        return stored
    }

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        entered.send(Unit)
        gate.receive()
        stored = transform(stored)
    }
}

/** `update(transform)` 계약을 그대로 흉내 내는 가짜 — 읽기·변환·쓰기가 한 호출 안에서 끝난다. */
private class FakeSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var failWith: IOException? = null

    override suspend fun load(): Settings = failWith?.let { throw it } ?: stored

    override suspend fun save(settings: Settings) {
        failWith?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        failWith?.let { throw it }
        stored = transform(stored)
    }
}

class PreferencesUseCaseSpec : FunSpec({

    test("설정 변경은 update 경로로 저장된 값 위에 부분 갱신된다") {
        val gateway = FakeSettingsGateway(STORED)

        val applied = runBlocking {
            UpdatePreferencesUseCase(gateway, AppliedSettings()).execute { it.copy(theme = ThemeMode.LIGHT) }
        }

        applied.theme shouldBe ThemeMode.LIGHT
        gateway.stored.theme shouldBe ThemeMode.LIGHT
        // 부분 갱신이므로 손대지 않은 값은 저장된 것이 그대로 남는다.
        gateway.stored.language shouldBe "en-GB"
        gateway.stored.identityProfiles shouldContainExactly listOf(PROFILE)
    }

    test("저장에 실패하면 예외가 올라오고 저장된 값은 바뀌지 않는다") {
        val gateway = FakeSettingsGateway(STORED)
        gateway.failWith = IOException("디스크가 가득 찼습니다")

        shouldThrow<IOException> {
            runBlocking {
                UpdatePreferencesUseCase(gateway, AppliedSettings()).execute { it.copy(theme = ThemeMode.LIGHT) }
            }
        }

        gateway.stored shouldBe STORED
    }

    test("설정 읽기는 저장된 값을 그대로 돌려준다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { LoadPreferencesUseCase(gateway).execute() } shouldBe STORED
    }

    test("전체 초기화는 화면·동작 취향과 탭 세션만 되돌린다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { UpdatePreferencesUseCase(gateway, AppliedSettings()).execute { it.withDefaultPreferences() } }

        gateway.stored.theme shouldBe Settings.DEFAULT_THEME
        gateway.stored.language shouldBe null
        gateway.stored.confirmDestructiveActions shouldBe true
        gateway.stored.updateCheck shouldBe UpdateCheckSettings.DEFAULT
        gateway.stored.openTabs shouldBe emptyList()
        gateway.stored.shortcutOverrides.shouldBeEmpty()
    }

    test("전체 초기화는 identity 프로필·외부 도구·최근 목록을 건드리지 않는다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { UpdatePreferencesUseCase(gateway, AppliedSettings()).execute { it.withDefaultPreferences() } }

        gateway.stored.identityProfiles shouldContainExactly listOf(PROFILE)
        gateway.stored.externalTools shouldBe TOOLS
        gateway.stored.recentRepositories shouldContainExactly listOf(RepositoryPath("/tmp/repo"))
    }

    // 설정 변경은 Git 연산이 아니므로 Undo 스택에 기록하지 않는다 (wave 8 결정 G11).
    // 초기화 경로가 SettingsGateway 하나만 만진다는 사실로 그 부재를 못박는다.
    test("전체 초기화는 SettingsGateway 만 호출한다 — Git 되돌리기 기록 경로가 없다") {
        val settingsGateway = mockk<SettingsGateway>()
        coEvery { settingsGateway.update(any()) } coAnswers {
            firstArg<(Settings) -> Settings>().invoke(STORED)
            Unit
        }

        runBlocking {
            UpdatePreferencesUseCase(settingsGateway, AppliedSettings()).execute { it.withDefaultPreferences() }
        }

        coVerify(exactly = 1) { settingsGateway.update(any()) }
        confirmVerified(settingsGateway)
    }

    test("서명 설정은 Git 설정이 주는 실효값을 그대로 읽어 온다") {
        val signingGateway = mockk<SigningGateway>()
        val signing = SigningSettings(
            signCommits = true,
            signTags = false,
            format = SigningFormat.SSH,
            signingKey = "~/.ssh/id_ed25519.pub",
        )
        coEvery { signingGateway.settings() } returns signing

        runBlocking { LoadSigningPreferencesUseCase(signingGateway).execute() } shouldBe signing
    }

    test("시작 읽기는 읽은 설정을 적용-설정 홀더에 싣는다") {
        val gateway = FakeSettingsGateway(STORED)
        val applied = AppliedSettings()

        // 배선(App)이 시작 시 하는 것과 같은 호출 — 읽기도 홀더의 직렬화 경로를 지난다.
        runBlocking { applied.publish { LoadPreferencesUseCase(gateway).execute() } } shouldBe STORED

        applied.current.value shouldBe STORED
    }

    test("적용-설정 홀더는 아직 아무것도 읽지 않았으면 비어 있다") {
        // 첫 프레임이 시스템 로케일·다크로 그려지는 근거다 — 배선은 값이 없을 때 기본값을 쓴다.
        AppliedSettings().current.value shouldBe null
    }

    test("성공한 갱신은 적용된 Settings 를 홀더에 발행한다") {
        val gateway = FakeSettingsGateway(STORED)
        val applied = AppliedSettings()

        runBlocking {
            UpdatePreferencesUseCase(gateway, applied).execute { it.copy(theme = ThemeMode.LIGHT, language = "en") }
        }

        applied.current.value?.theme shouldBe ThemeMode.LIGHT
        applied.current.value?.language shouldBe "en"
    }

    test("저장에 실패하면 새 값을 발행하지 않고 이전 적용값을 유지한다") {
        val gateway = FakeSettingsGateway(STORED)
        val applied = AppliedSettings()
        runBlocking { applied.publish { LoadPreferencesUseCase(gateway).execute() } }
        gateway.failWith = IOException("디스크가 가득 찼습니다")

        shouldThrow<IOException> {
            runBlocking { UpdatePreferencesUseCase(gateway, applied).execute { it.copy(theme = ThemeMode.LIGHT) } }
        }

        // 저장되지 않은 값을 화면에 적용하면 재기동 후 되돌아가 사용자가 이유를 알 수 없다.
        applied.current.value shouldBe STORED
    }

    test("겹친 두 갱신은 커밋 순서대로 발행되어 마지막 커밋만 최종 적용값으로 남는다") {
        runBlocking {
            val gateway = GatedSettingsGateway(STORED)
            val applied = AppliedSettings()
            val useCase = UpdatePreferencesUseCase(gateway, applied)

            val first = launch { useCase.execute { it.copy(language = "ko") } }
            gateway.entered.receive()
            // 첫 갱신이 gateway 안에 머무는 동안 두 번째가 시작한다.
            val second = launch { useCase.execute { it.copy(language = "en") } }
            repeat(YIELD_ROUNDS) { yield() }

            // 두 번째는 첫 갱신이 발행을 끝낼 때까지 gateway 에 닿지 못한다 — 직렬화가 없으면
            // 여기서 이미 들어와 있고, 커밋 순서와 발행 순서가 어긋날 창이 열린다.
            gateway.entered.tryReceive().isFailure shouldBe true

            gateway.gate.send(Unit)
            first.join()
            applied.current.value?.language shouldBe "ko"

            gateway.gate.send(Unit)
            second.join()
            applied.current.value?.language shouldBe "en"
            gateway.stored.language shouldBe "en"
        }
    }

    test("갱신과 겹친 시작 읽기는 늦게 끝나도 새 적용값을 덮지 않는다") {
        runBlocking {
            val gateway = GatedSettingsGateway(STORED)
            val applied = AppliedSettings()

            // 읽기가 먼저 임계구역을 잡는다 — 갱신은 읽기가 발행을 끝낼 때까지 시작하지 못한다.
            val load = launch { applied.publish { LoadPreferencesUseCase(gateway).execute() } }
            gateway.entered.receive()
            val update = launch {
                UpdatePreferencesUseCase(gateway, applied).execute { it.copy(theme = ThemeMode.LIGHT) }
            }
            repeat(YIELD_ROUNDS) { yield() }

            // 읽기가 발행을 끝내기 전에는 갱신이 저장을 시작하지 못한다 — 두 경로가 같은 직렬화를 지난다.
            gateway.entered.tryReceive().isFailure shouldBe true

            gateway.gate.send(Unit)
            load.join()
            gateway.gate.send(Unit)
            update.join()

            applied.current.value?.theme shouldBe ThemeMode.LIGHT
        }
    }
})
