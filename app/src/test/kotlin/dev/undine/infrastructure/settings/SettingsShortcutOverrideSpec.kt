package dev.undine.infrastructure.settings

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey
import dev.undine.domain.ThemeMode
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.WindowBounds
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private const val FIXED_NOW = 1_700_000_000_000L
private const val REFRESH_COMMAND = "graph.refresh"
private const val PALETTE_COMMAND = "palette.open"
private const val ZOOM_COMMAND = "graph.zoom-in"

private fun settingsFileIn(directory: File): Path =
    directory.toPath().resolve("undine").resolve("settings.json")

private fun gatewayFor(settingsFile: Path) =
    SettingsGatewayImpl(settingsFile = settingsFile, currentTimeMillis = { FIXED_NOW })

private fun newerSchemaBackupOf(settingsFile: Path): Path =
    settingsFile.resolveSibling("settings.json.newer-$FIXED_NOW")

private fun writeFile(target: Path, content: String) {
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

private val OVERRIDES = mapOf(
    REFRESH_COMMAND to ShortcutBinding(
        keyCode = KeyEvent.VK_R,
        modifiers = setOf(ShortcutModifierKey.PRIMARY, ShortcutModifierKey.SHIFT),
    ),
    PALETTE_COMMAND to ShortcutBinding(keyCode = KeyEvent.VK_P, modifiers = setOf(ShortcutModifierKey.ALT)),
)

/** 키패드 자리에서 잡은 단축키. [KeyEvent.KEY_LOCATION_STANDARD] 가 아니라 기본값에 기대 읽을 수 없다. */
private val NUMPAD_OVERRIDE = ZOOM_COMMAND to ShortcutBinding(
    keyCode = KeyEvent.VK_ADD,
    modifiers = setOf(ShortcutModifierKey.PRIMARY),
    keyLocation = KeyEvent.KEY_LOCATION_NUMPAD,
)

/** wave 8 이 넓힌 자리를 전부 비기본값으로 채운 설정. 오버라이드만 따로 왕복시키면 결합을 못 본다. */
private val NON_DEFAULT_SETTINGS = Settings.DEFAULTS.copy(
    recentRepositories = listOf(RepositoryPath("/tmp/repo")),
    theme = ThemeMode.LIGHT,
    window = WindowBounds(width = 1440, height = 900, maximized = true),
    identityProfiles = listOf(
        IdentityProfile(
            name = "일 이름",
            email = "work@example.com",
            signingKeyId = "ABCD1234",
            defaultAuthentication = AuthenticationMethod.SSH,
            expectedHost = "github.com",
        ),
    ),
    externalTools = ExternalToolSettings(
        diffTool = ExternalTool(executable = "/usr/bin/kdiff3", arguments = listOf("\$LOCAL", "\$REMOTE")),
        mergeTool = null,
    ),
    language = "en-GB",
    reopenLastRepository = true,
    confirmDestructiveActions = false,
    openTabs = listOf(RepositoryPath("/tmp/tab"), RepositoryPath("/tmp/tab")),
    activeTabIndex = 1,
    updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 72),
    shortcutOverrides = OVERRIDES + NUMPAD_OVERRIDE,
)

/** 스키마 4 를 쓰던 앱의 원본. 스키마 3 앱으로 내려가 저장하면 오버라이드가 이 백업에만 남는다. */
private val SCHEMA_4_BACKUP_CONTENT = """
    {
      "schemaVersion": 4,
      "recentRepositories": [],
      "theme": "DARK",
      "window": { "width": 1440, "height": 900, "maximized": false },
      "identityProfiles": [],
      "externalTools": { "diffTool": null, "mergeTool": null },
      "language": "ko-KR",
      "reopenLastRepository": false,
      "confirmDestructiveActions": true,
      "openTabs": [],
      "activeTabIndex": 0,
      "updateCheck": { "enabled": true, "intervalHours": 24 },
      "shortcutOverrides": {
        "graph.refresh": { "keyCode": 82, "keyLocation": 1, "modifiers": ["PRIMARY", "SHIFT"] }
      }
    }
""".trimIndent()

/**
 * 단축키 오버라이드 매핑의 저장 왕복. wave 8 필드와 같은 규칙을 따른다 —
 * 알 수 없는 키는 무시하고, 읽을 수 없는 값은 그 항목만 기본값(= 오버라이드 없음)이 된다.
 */
class SettingsShortcutOverrideSpec : FunSpec({

    test("단축키 오버라이드 매핑은 저장하고 다시 읽어도 커맨드 id 별 값이 그대로다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(Settings.DEFAULTS.copy(shortcutOverrides = OVERRIDES))

        gatewayFor(settingsFile).load().shortcutOverrides shouldBe OVERRIDES
    }

    test("비기본 wave-8 필드와 키패드 오버라이드를 함께 저장해도 설정 전체가 그대로 왕복한다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(NON_DEFAULT_SETTINGS)

        gatewayFor(settingsFile).load() shouldBe NON_DEFAULT_SETTINGS
    }

    test("키패드 자리 오버라이드는 같은 키 코드의 표준 자리 오버라이드와 섞이지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        val sameKeyCode = mapOf(
            ZOOM_COMMAND to ShortcutBinding(keyCode = KeyEvent.VK_ADD, keyLocation = KeyEvent.KEY_LOCATION_NUMPAD),
            PALETTE_COMMAND to ShortcutBinding(keyCode = KeyEvent.VK_ADD),
        )

        gatewayFor(settingsFile).save(Settings.DEFAULTS.copy(shortcutOverrides = sameKeyCode))

        gatewayFor(settingsFile).load().shortcutOverrides shouldBe sameKeyCode
    }

    test("오버라이드 매핑 키가 없는 기존 파일은 빈 매핑으로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "schemaVersion": 3, "theme": "DARK" }""")

        gatewayFor(settingsFile).load().shortcutOverrides.shouldBeEmpty()
    }

    test("매핑 전체의 타입이 어긋나면 그 필드만 빈 매핑이 되고 나머지는 보존된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "schemaVersion": 4, "theme": "DARK", "shortcutOverrides": "없음" }""")

        val loaded = gatewayFor(settingsFile).load()

        loaded.shortcutOverrides.shouldBeEmpty()
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("읽을 수 없는 항목은 그 항목만 버리고 나머지 오버라이드는 살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 4,
              "shortcutOverrides": {
                "graph.refresh": { "keyCode": 82, "modifiers": ["PRIMARY"] },
                "no.keycode": { "modifiers": ["PRIMARY"] },
                "unknown.modifier": { "keyCode": 75, "modifiers": ["HYPER"] },
                "not.an.object": 7
              }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.shortcutOverrides shouldBe mapOf(
            REFRESH_COMMAND to ShortcutBinding(
                keyCode = KeyEvent.VK_R,
                modifiers = setOf(ShortcutModifierKey.PRIMARY),
            ),
        )
    }

    test("수식키가 없는 오버라이드도 그대로 왕복한다") {
        val settingsFile = settingsFileIn(tempdir())
        val bare = mapOf(REFRESH_COMMAND to ShortcutBinding(keyCode = KeyEvent.VK_F5))

        gatewayFor(settingsFile).save(Settings.DEFAULTS.copy(shortcutOverrides = bare))

        gatewayFor(settingsFile).load().shortcutOverrides shouldBe bare
    }

    test("스키마 4 로 저장한 파일은 현재 스키마 버전을 적는다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(Settings.DEFAULTS.copy(shortcutOverrides = OVERRIDES))

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["schemaVersion"] shouldBe CURRENT_SCHEMA_VERSION.toLong()
    }

    test("스키마 3 으로 롤백했다 돌아오면 구버전이 담지 못한 오버라이드를 백업에서 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_4_BACKUP_CONTENT)
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 3,
              "theme": "LIGHT",
              "language": "en-GB",
              "updateCheck": { "enabled": true, "intervalHours": 24 }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.shortcutOverrides shouldBe mapOf(
            REFRESH_COMMAND to ShortcutBinding(
                keyCode = KeyEvent.VK_R,
                modifiers = setOf(ShortcutModifierKey.PRIMARY, ShortcutModifierKey.SHIFT),
            ),
        )
        // 스키마 3 이 아는 필드는 구버전 파일이 이긴다 — 사용자가 구버전에서 실제로 고친 값이다.
        loaded.language shouldBe "en-GB"
        loaded.theme shouldBe ThemeMode.LIGHT
    }
})
