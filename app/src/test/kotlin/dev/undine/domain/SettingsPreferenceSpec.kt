package dev.undine.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.awt.event.KeyEvent

private const val REFRESH_COMMAND = "graph.refresh"
private const val COMMIT_COMMAND = "staging.commit"

private val REFRESH_OVERRIDE = ShortcutBinding(
    keyCode = KeyEvent.VK_R,
    modifiers = setOf(ShortcutModifierKey.PRIMARY, ShortcutModifierKey.SHIFT),
)

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

/** 사용자가 전부 바꿔 둔 설정. 초기화가 무엇을 되돌리고 무엇을 남기는지 이 값으로 가른다. */
private val CUSTOMIZED = Settings.DEFAULTS.copy(
    recentRepositories = listOf(RepositoryPath("/tmp/repo")),
    theme = ThemeMode.DARK,
    window = WindowBounds(width = 1440, height = 900, maximized = true),
    identityProfiles = listOf(PROFILE),
    externalTools = TOOLS,
    language = "en-GB",
    reopenLastRepository = true,
    confirmDestructiveActions = false,
    openTabs = listOf(RepositoryPath("/tmp/tab-a"), RepositoryPath("/tmp/tab-b")),
    activeTabIndex = 1,
    updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 72),
    shortcutOverrides = mapOf(REFRESH_COMMAND to REFRESH_OVERRIDE),
)

class SettingsPreferenceSpec : FunSpec({

    test("단축키 오버라이드는 커맨드 id 별 값을 그대로 보존한다") {
        CUSTOMIZED.shortcutOverrides.getValue(REFRESH_COMMAND) shouldBe REFRESH_OVERRIDE
    }

    test("오버라이드하지 않은 커맨드는 매핑에 항목을 만들지 않는다") {
        CUSTOMIZED.shortcutOverrides.containsKey(COMMIT_COMMAND) shouldBe false
        Settings.DEFAULTS.shortcutOverrides.shouldBeEmpty()
    }

    test("항목별 기본값 복원은 그 항목만 되돌리고 나머지는 유지한다") {
        val restored = CUSTOMIZED.withDefault(SettingsPreference.THEME)

        restored.theme shouldBe Settings.DEFAULT_THEME
        restored.language shouldBe "en-GB"
        restored.confirmDestructiveActions shouldBe false
        restored.shortcutOverrides shouldBe mapOf(REFRESH_COMMAND to REFRESH_OVERRIDE)
        restored.identityProfiles shouldContainExactly listOf(PROFILE)
    }

    test("단축키 오버라이드만 되돌려도 다른 취향은 그대로다") {
        val restored = CUSTOMIZED.withDefault(SettingsPreference.SHORTCUT_OVERRIDES)

        restored.shortcutOverrides.shouldBeEmpty()
        restored.theme shouldBe ThemeMode.DARK
        restored.updateCheck shouldBe UpdateCheckSettings(enabled = false, intervalHours = 72)
    }

    test("identity 프로필과 외부 도구는 항목별 복원으로만 비워진다") {
        CUSTOMIZED.withDefault(SettingsPreference.IDENTITY_PROFILES).identityProfiles shouldBe emptyList()
        CUSTOMIZED.withDefault(SettingsPreference.EXTERNAL_TOOLS).externalTools shouldBe ExternalToolSettings.NONE
    }

    test("전체 초기화는 화면·동작 취향과 탭 세션만 기본값으로 되돌린다") {
        val reset = CUSTOMIZED.withDefaultPreferences()

        reset.theme shouldBe Settings.DEFAULT_THEME
        reset.language shouldBe null
        reset.reopenLastRepository shouldBe false
        reset.confirmDestructiveActions shouldBe true
        reset.shortcutOverrides.shouldBeEmpty()
        reset.updateCheck shouldBe UpdateCheckSettings.DEFAULT
        reset.openTabs shouldBe emptyList()
        reset.activeTabIndex shouldBe 0
    }

    test("전체 초기화는 identity 프로필·외부 도구·최근 목록·창 상태를 건드리지 않는다") {
        val reset = CUSTOMIZED.withDefaultPreferences()

        reset.identityProfiles shouldContainExactly listOf(PROFILE)
        reset.externalTools shouldBe TOOLS
        reset.recentRepositories shouldContainExactly listOf(RepositoryPath("/tmp/repo"))
        reset.window shouldBe WindowBounds(width = 1440, height = 900, maximized = true)
    }

    test("전체 초기화 대상 목록에 identity·외부 도구가 없다 — 되돌릴 수 없는 값은 항목별로만 지운다") {
        RESET_ALL_PREFERENCES.contains(SettingsPreference.IDENTITY_PROFILES) shouldBe false
        RESET_ALL_PREFERENCES.contains(SettingsPreference.EXTERNAL_TOOLS) shouldBe false
    }
})
