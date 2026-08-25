package dev.undine.infrastructure.settings

import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.WindowBounds
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private const val FIXED_NOW = 1_700_000_000_000L

private fun settingsFileIn(directory: File): Path =
    directory.toPath().resolve("undine").resolve("settings.json")

private fun gatewayFor(settingsFile: Path, now: Long = FIXED_NOW) =
    SettingsGatewayImpl(settingsFile = settingsFile, currentTimeMillis = { now })

private fun backupOf(settingsFile: Path, now: Long = FIXED_NOW): Path =
    settingsFile.resolveSibling("settings.json.corrupt-$now")

private fun newerSchemaBackupOf(settingsFile: Path, now: Long = FIXED_NOW): Path =
    settingsFile.resolveSibling("settings.json.newer-$now")

private val DEFAULTS = Settings(
    recentRepositories = emptyList(),
    theme = ThemeMode.SYSTEM,
    window = WindowBounds(width = 1280, height = 800, maximized = false),
)

private fun writeFile(target: Path, content: String) {
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

/** wave 8 이 더한 필드를 모두 채운 설정. 저장-재로딩 왕복 검증에 쓴다. */
private val WAVE8_SETTINGS = DEFAULTS.copy(
    language = "en-GB",
    reopenLastRepository = true,
    confirmDestructiveActions = false,
    openTabs = listOf(RepositoryPath("/tmp/tab-a"), RepositoryPath("/tmp/tab-b")),
    activeTabIndex = 1,
    updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 72),
)

/**
 * 스키마 3 을 쓰던 앱의 원본. 스키마 2 앱으로 내려가 저장하면 이 내용이 newer 백업으로 밀려난다 —
 * 스키마 2 는 wave 8 필드를 담을 수 없으므로 그 필드만 되살아나야 한다.
 */
private val SCHEMA_3_BACKUP_CONTENT = """
    {
      "schemaVersion": 3,
      "recentRepositories": ["/tmp/before-rollback"],
      "theme": "DARK",
      "window": { "width": 1440, "height": 900, "maximized": false },
      "identityProfiles": [
        { "name": "백업 프로필", "email": "backup@example.com", "defaultAuthentication": "SSH" }
      ],
      "externalTools": { "diffTool": null, "mergeTool": null },
      "language": "ko-KR",
      "reopenLastRepository": true,
      "confirmDestructiveActions": false,
      "openTabs": ["/tmp/tab-from-backup"],
      "activeTabIndex": 0,
      "updateCheck": { "enabled": false, "intervalHours": 72 }
    }
""".trimIndent()

/**
 * 스키마 2 앱에서 사용자가 수정한 값. 3 → 2 → 1 연속 다운그레이드에서는 이것이 더 최근 백업이지만,
 * wave 8 필드는 표현할 수 없으므로 스키마 3 백업에서만 복원해야 한다.
 */
private val SCHEMA_2_BACKUP_CONTENT = """
    {
      "schemaVersion": 2,
      "recentRepositories": ["/tmp/schema-2-recent"],
      "theme": "LIGHT",
      "window": { "width": 1280, "height": 800, "maximized": false },
      "identityProfiles": [
        { "name": "스키마 2 프로필", "email": "schema2@example.com", "defaultAuthentication": "HTTPS" }
      ],
      "externalTools": { "diffTool": null, "mergeTool": null }
    }
""".trimIndent()

/**
 * UND-63 이 넓힌 wave 8 설정 필드(언어·시작 동작·확인 대화상자·탭 세션·업데이트 확인)의 영속화.
 *
 * `SettingsGatewayImplSpec` 과 파일을 나눈 이유는 그 클래스가 이미 detekt `LargeClass` 상한에
 * 닿아서다 — 검증 대상은 같은 Gateway 이고, 실제 임시 디렉토리에 파일을 쓰고 읽는 방식도 같다.
 */
class SettingsWave8FieldsSpec : FunSpec({

    test("wave 8 필드가 없는 구 스키마 파일은 그 필드만 기본값으로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 2,
              "recentRepositories": ["/tmp/kept"],
              "theme": "DARK",
              "window": { "width": 1024, "height": 768, "maximized": true }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.language shouldBe null
        loaded.reopenLastRepository shouldBe false
        loaded.confirmDestructiveActions shouldBe true
        loaded.openTabs.shouldBeEmpty()
        loaded.activeTabIndex shouldBe 0
        loaded.updateCheck shouldBe UpdateCheckSettings.DEFAULT
        // 구 스키마가 담고 있던 값은 그대로 살아 있어야 한다.
        loaded.recentRepositories shouldBe listOf(RepositoryPath("/tmp/kept"))
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("wave 8 필드는 저장 후 다시 읽으면 같은 값으로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(WAVE8_SETTINGS)

        gatewayFor(settingsFile).load() shouldBe WAVE8_SETTINGS
    }

    test("언어를 지정하지 않은 설정은 null 로 왕복한다 — 빈 문자열로 둔갑하지 않는다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(DEFAULTS.copy(language = null))

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["language"] shouldBe null
        gatewayFor(settingsFile).load().language shouldBe null
    }

    test("경로가 사라진 탭도 설정에서 제거되지 않고 저장 후 그대로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())
        val missing = RepositoryPath("/tmp/undine-missing-tab-repository")
        val settings = DEFAULTS.copy(
            openTabs = listOf(missing, RepositoryPath("/tmp/tab-b")),
            activeTabIndex = 1,
        )

        gatewayFor(settingsFile).save(settings)

        Files.exists(Path.of(missing.value)) shouldBe false
        gatewayFor(settingsFile).load().openTabs shouldContainExactly settings.openTabs
    }

    test("탭 목록은 최근 저장소와 달리 중복 제거·상한 절단을 하지 않는다") {
        // 같은 저장소를 두 탭으로 여는 것은 사용자의 선택이다 — 저장이 조용히 하나로 합치면 탭이 사라진다.
        val settingsFile = settingsFileIn(tempdir())
        val duplicated = listOf(RepositoryPath("/tmp/same"), RepositoryPath("/tmp/same"))

        gatewayFor(settingsFile).save(DEFAULTS.copy(openTabs = duplicated, activeTabIndex = 1))

        gatewayFor(settingsFile).load().openTabs shouldContainExactly duplicated
    }

    test("활성 탭 인덱스가 탭 범위를 벗어나면 0 으로 클램프한다 — 오류가 아니다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 3,
              "openTabs": ["/tmp/only-tab"],
              "activeTabIndex": 7
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.activeTabIndex shouldBe 0
        loaded.openTabs shouldContainExactly listOf(RepositoryPath("/tmp/only-tab"))
    }

    test("음수 활성 탭 인덱스도 0 으로 클램프한다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """{ "schemaVersion": 3, "openTabs": ["/tmp/a", "/tmp/b"], "activeTabIndex": -3 }""",
        )

        gatewayFor(settingsFile).load().activeTabIndex shouldBe 0
    }

    test("업데이트 확인 주기가 허용 범위 밖이면 기본값으로 클램프한다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """{ "schemaVersion": 3, "updateCheck": { "enabled": true, "intervalHours": 0 } }""",
        )

        gatewayFor(settingsFile).load().updateCheck shouldBe
            UpdateCheckSettings(enabled = true, intervalHours = UpdateCheckSettings.DEFAULT.intervalHours)
    }

    test("허용 범위 경계값(1시간·168시간)은 그대로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())

        listOf(1, 168).forEach { hours ->
            gatewayFor(settingsFile).save(
                DEFAULTS.copy(updateCheck = UpdateCheckSettings(enabled = true, intervalHours = hours)),
            )

            gatewayFor(settingsFile).load().updateCheck.intervalHours shouldBe hours
        }
    }

    test("wave 8 필드의 타입이 어긋나면 그 필드만 기본값이 되고 나머지는 보존된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 3,
              "theme": "DARK",
              "language": 42,
              "reopenLastRepository": "yes",
              "openTabs": "문자열 탭",
              "activeTabIndex": "첫번째",
              "updateCheck": "켜짐"
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.language shouldBe null
        loaded.reopenLastRepository shouldBe false
        loaded.openTabs.shouldBeEmpty()
        loaded.activeTabIndex shouldBe 0
        loaded.updateCheck shouldBe UpdateCheckSettings.DEFAULT
        loaded.theme shouldBe ThemeMode.DARK
    }

    test("깨진 JSON 은 여전히 손상으로 격리된다 — 신규 필드 추가가 실패 계약을 무르게 하지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        val corruptContent = """{ "schemaVersion": 3, "openTabs": [ """
        writeFile(settingsFile, corruptContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        Files.readString(backupOf(settingsFile)) shouldBe corruptContent
    }

    test("스키마 3 으로 롤백했다 돌아오면 구버전이 담지 못한 wave 8 필드를 백업에서 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_3_BACKUP_CONTENT)
        // 스키마 2 앱이 새로 쓴 파일이다 — wave 8 필드를 담을 수 없었다.
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 2,
              "recentRepositories": ["/tmp/opened-on-old-version"],
              "theme": "LIGHT",
              "window": { "width": 1024, "height": 768, "maximized": false },
              "identityProfiles": [
                { "name": "구버전에서 고침", "email": "edited@example.com", "defaultAuthentication": "HTTPS" }
              ]
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.language shouldBe "ko-KR"
        loaded.reopenLastRepository shouldBe true
        loaded.confirmDestructiveActions shouldBe false
        loaded.openTabs shouldContainExactly listOf(RepositoryPath("/tmp/tab-from-backup"))
        loaded.updateCheck shouldBe UpdateCheckSettings(enabled = false, intervalHours = 72)
        // 스키마 2 가 아는 필드는 구버전 파일이 이긴다 — 사용자가 구버전에서 실제로 고친 값이다.
        loaded.identityProfiles.single().name shouldBe "구버전에서 고침"
        loaded.recentRepositories shouldBe listOf(RepositoryPath("/tmp/opened-on-old-version"))
    }

    test("되살린 wave 8 필드는 저장하면 현재 스키마 파일에 다시 담긴다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_3_BACKUP_CONTENT)
        writeFile(settingsFile, """{ "schemaVersion": 2, "theme": "LIGHT" }""")
        val gateway = gatewayFor(settingsFile)

        gateway.save(gateway.load())

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["schemaVersion"] shouldBe CURRENT_SCHEMA_VERSION.toLong()
        gatewayFor(settingsFile).load().language shouldBe "ko-KR"
    }

    test("3 에서 2 와 1 로 연속 다운그레이드한 뒤 저장해도 각 백업의 최신 표현 가능 필드를 보존한다") {
        val settingsFile = settingsFileIn(tempdir())
        // 3 → 2 에서 만들어진 백업은 더 오래됐지만 wave 8 필드를 표현할 수 있는 유일한 원본이다.
        writeFile(newerSchemaBackupOf(settingsFile, now = FIXED_NOW - 1), SCHEMA_3_BACKUP_CONTENT)
        // 2 → 1 에서 만들어진 더 최근 백업은 스키마 2 가 알고 있던 값의 최신 사용자 변경이다.
        writeFile(newerSchemaBackupOf(settingsFile), SCHEMA_2_BACKUP_CONTENT)
        writeFile(settingsFile, """{ "schemaVersion": 1, "theme": "DARK" }""")
        val gateway = gatewayFor(settingsFile)

        val restored = gateway.load()
        restored.identityProfiles.single().name shouldBe "스키마 2 프로필"
        restored.language shouldBe "ko-KR"
        restored.openTabs shouldContainExactly listOf(RepositoryPath("/tmp/tab-from-backup"))
        restored.updateCheck shouldBe UpdateCheckSettings(enabled = false, intervalHours = 72)

        gateway.save(restored)

        gatewayFor(settingsFile).load() shouldBe restored
    }
})
