package dev.undine.infrastructure.settings

import dev.undine.domain.AutomaticFetchSettings
import dev.undine.domain.PullStrategy
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private const val FIXED_NOW = 1_700_000_000_000L

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

private val DEFAULTS = Settings(
    recentRepositories = emptyList(),
    theme = ThemeMode.SYSTEM,
    window = WindowBounds(width = 1280, height = 800, maximized = false),
)

/** UND-74 가 더한 탭 값을 전부 기본값과 다르게 채운 설정. 저장-재로딩 왕복 검증에 쓴다. */
private val TAB_VALUE_SETTINGS = DEFAULTS.copy(
    defaultBranchName = "trunk",
    pullStrategy = PullStrategy.REBASE,
    automaticFetch = AutomaticFetchSettings(enabled = true, intervalMinutes = 30),
    tabWidth = 8,
    monospaceFontFamily = "JetBrains Mono",
    largeFileThresholdBytes = 32L * 1024 * 1024,
    commitPageSize = 500,
)

/**
 * 스키마 4 를 쓰던 앱의 원본. 스키마 5 필드를 담을 수 없으므로 롤백 복구 대상이다.
 */
private val SCHEMA_5_BACKUP_CONTENT = """
    {
      "schemaVersion": 5,
      "theme": "DARK",
      "window": { "width": 1440, "height": 900, "maximized": false },
      "defaultBranchName": "trunk",
      "pullStrategy": "REBASE",
      "automaticFetch": { "enabled": true, "intervalMinutes": 45 },
      "tabWidth": 2,
      "monospaceFontFamily": "Fira Code",
      "largeFileThresholdBytes": 5242880,
      "commitPageSize": 250
    }
""".trimIndent()

/**
 * UND-74 가 넓힌 탭 값(기본 브랜치·pull 방식·자동 fetch·탭 폭·고정폭 서체·대용량 임계치·페이지 크기)의
 * 영속화. 스키마 4 → 5 마이그레이션과 왕복을 실제 임시 파일로 본다.
 *
 * **읽기는 어떤 값에도 실패하지 않는다.** `Settings` 가 범위를 `require` 로 거부하므로, 손으로 고친
 * 파일의 0 이하 값이 그대로 생성자에 들어가면 설정을 아예 읽을 수 없게 된다. 범위 밖은 오류가 아니라
 * **그 필드만 기본값**이다 — 업데이트 확인 주기와 같은 규약이다.
 */
class SettingsTabValueCodecSpec : FunSpec({

    test("탭 값은 저장 후 다시 읽으면 같은 값으로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(TAB_VALUE_SETTINGS)

        gatewayFor(settingsFile).load() shouldBe TAB_VALUE_SETTINGS
    }

    test("현재 스키마 버전은 5 다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(DEFAULTS)

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["schemaVersion"] shouldBe 5L
        CURRENT_SCHEMA_VERSION shouldBe 5
    }

    test("스키마 4 파일을 읽으면 탭 값이 결정된 기본값으로 채워진다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 4,
              "recentRepositories": ["/tmp/kept"],
              "theme": "DARK",
              "language": "ko-KR"
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.defaultBranchName shouldBe "main"
        loaded.pullStrategy shouldBe PullStrategy.MERGE
        loaded.automaticFetch shouldBe AutomaticFetchSettings.DEFAULT
        loaded.tabWidth shouldBe 4
        loaded.monospaceFontFamily.shouldBeNull()
        loaded.largeFileThresholdBytes shouldBe 1024L * 1024
        loaded.commitPageSize shouldBe 100
        // 스키마 4 가 담고 있던 값은 그대로 살아 있어야 한다.
        loaded.theme shouldBe ThemeMode.DARK
        loaded.language shouldBe "ko-KR"
    }

    test("고정폭 서체를 지정하지 않은 설정은 null 로 왕복한다 — 빈 문자열로 둔갑하지 않는다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(DEFAULTS.copy(monospaceFontFamily = null))

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["monospaceFontFamily"] shouldBe null
        gatewayFor(settingsFile).load().monospaceFontFamily.shouldBeNull()
    }

    test("자동 fetch 를 꺼도 주기 값은 파일에 남아 껐다 켤 때 되찾을 수 있다") {
        val settingsFile = settingsFileIn(tempdir())
        val stopped = AutomaticFetchSettings(enabled = false, intervalMinutes = 45)

        gatewayFor(settingsFile).save(DEFAULTS.copy(automaticFetch = stopped))

        gatewayFor(settingsFile).load().automaticFetch shouldBe stopped
    }

    test("빈 고정폭 서체 이름은 그대로 왕복한다 — null 로 접혀 시스템 기본으로 둔갑하지 않는다") {
        listOf("", "   ").forEach { blank ->
            val settingsFile = settingsFileIn(tempdir())

            gatewayFor(settingsFile).save(DEFAULTS.copy(monospaceFontFamily = blank))

            gatewayFor(settingsFile).load().monospaceFontFamily shouldBe blank
        }
    }

    test("꺼진 fetch 의 0 이하 주기는 파일 손상 방어로만 기본값이 된다 — 앱이 만들 수 없는 값이다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 5,
              "theme": "DARK",
              "automaticFetch": { "enabled": false, "intervalMinutes": 0 }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.automaticFetch shouldBe AutomaticFetchSettings.DEFAULT
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("알 수 없는 pull 방식 문자열은 기본값으로 읽는다 — 설정 하나 때문에 파일을 못 읽지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "schemaVersion": 5, "theme": "DARK", "pullStrategy": "SQUASH" }""")

        val loaded = gatewayFor(settingsFile).load()

        loaded.pullStrategy shouldBe PullStrategy.MERGE
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("범위를 벗어난 수치는 그 필드만 기본값이 되고 나머지는 보존된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 5,
              "theme": "DARK",
              "tabWidth": 0,
              "largeFileThresholdBytes": -1,
              "commitPageSize": -20,
              "automaticFetch": { "enabled": true, "intervalMinutes": 0 }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.tabWidth shouldBe 4
        loaded.largeFileThresholdBytes shouldBe 1024L * 1024
        loaded.commitPageSize shouldBe 100
        loaded.automaticFetch shouldBe AutomaticFetchSettings.DEFAULT
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("빈 기본 브랜치명은 기본값으로 읽는다 — 읽기가 생성자 거부로 실패하지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "schemaVersion": 5, "defaultBranchName": "   " }""")

        gatewayFor(settingsFile).load().defaultBranchName shouldBe "main"
    }

    test("탭 값의 타입이 어긋나면 그 필드만 기본값이 된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 5,
              "defaultBranchName": 42,
              "pullStrategy": 7,
              "automaticFetch": "켜짐",
              "tabWidth": "넷",
              "monospaceFontFamily": 12,
              "largeFileThresholdBytes": "많이",
              "commitPageSize": [100]
            }
            """.trimIndent(),
        )

        gatewayFor(settingsFile).load() shouldBe DEFAULTS
    }

    test("스키마 5 로 롤백했다 돌아오면 구버전이 담지 못한 탭 값을 백업에서 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_5_BACKUP_CONTENT)
        // 스키마 4 앱이 새로 쓴 파일이다 — 탭 값을 담을 수 없었다.
        writeFile(
            settingsFile,
            """{ "schemaVersion": 4, "theme": "LIGHT", "language": "en-GB" }""",
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.defaultBranchName shouldBe "trunk"
        loaded.pullStrategy shouldBe PullStrategy.REBASE
        loaded.automaticFetch shouldBe AutomaticFetchSettings(enabled = true, intervalMinutes = 45)
        loaded.tabWidth shouldBe 2
        loaded.monospaceFontFamily shouldBe "Fira Code"
        loaded.largeFileThresholdBytes shouldBe 5_242_880L
        loaded.commitPageSize shouldBe 250
        // 스키마 4 가 아는 필드는 구버전 파일이 이긴다 — 사용자가 구버전에서 실제로 고친 값이다.
        loaded.theme shouldBe ThemeMode.LIGHT
        loaded.language shouldBe "en-GB"
    }

    test("롤백 백업의 꺼진 fetch 주기와 빈 서체 이름도 손실 없이 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            newerSchemaBackupOf(settingsFile),
            """
            {
              "schemaVersion": 5,
              "automaticFetch": { "enabled": false, "intervalMinutes": 45 },
              "monospaceFontFamily": ""
            }
            """.trimIndent(),
        )
        writeFile(settingsFile, """{ "schemaVersion": 4, "theme": "LIGHT" }""")

        val loaded = gatewayFor(settingsFile).load()

        loaded.automaticFetch shouldBe AutomaticFetchSettings(enabled = false, intervalMinutes = 45)
        loaded.monospaceFontFamily shouldBe ""
    }

    test("되살린 탭 값은 저장하면 현재 스키마 파일에 다시 담긴다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_5_BACKUP_CONTENT)
        writeFile(settingsFile, """{ "schemaVersion": 4, "theme": "LIGHT" }""")
        val gateway = gatewayFor(settingsFile)

        gateway.save(gateway.load())

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["schemaVersion"] shouldBe CURRENT_SCHEMA_VERSION.toLong()
        gatewayFor(settingsFile).load().defaultBranchName shouldBe "trunk"
    }
})
