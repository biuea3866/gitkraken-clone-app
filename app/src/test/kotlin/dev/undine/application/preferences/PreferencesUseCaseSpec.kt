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
import kotlinx.coroutines.runBlocking
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
            UpdatePreferencesUseCase(gateway).execute { it.copy(theme = ThemeMode.LIGHT) }
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
            runBlocking { UpdatePreferencesUseCase(gateway).execute { it.copy(theme = ThemeMode.LIGHT) } }
        }

        gateway.stored shouldBe STORED
    }

    test("설정 읽기는 저장된 값을 그대로 돌려준다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { LoadPreferencesUseCase(gateway).execute() } shouldBe STORED
    }

    test("전체 초기화는 화면·동작 취향과 탭 세션만 되돌린다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { UpdatePreferencesUseCase(gateway).execute { it.withDefaultPreferences() } }

        gateway.stored.theme shouldBe Settings.DEFAULT_THEME
        gateway.stored.language shouldBe null
        gateway.stored.confirmDestructiveActions shouldBe true
        gateway.stored.updateCheck shouldBe UpdateCheckSettings.DEFAULT
        gateway.stored.openTabs shouldBe emptyList()
        gateway.stored.shortcutOverrides.shouldBeEmpty()
    }

    test("전체 초기화는 identity 프로필·외부 도구·최근 목록을 건드리지 않는다") {
        val gateway = FakeSettingsGateway(STORED)

        runBlocking { UpdatePreferencesUseCase(gateway).execute { it.withDefaultPreferences() } }

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

        runBlocking { UpdatePreferencesUseCase(settingsGateway).execute { it.withDefaultPreferences() } }

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
})
