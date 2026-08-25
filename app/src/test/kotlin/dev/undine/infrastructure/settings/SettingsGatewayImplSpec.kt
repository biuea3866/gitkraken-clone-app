package dev.undine.infrastructure.settings

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.IdentityProfile
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val FIXED_NOW = 1_700_000_000_000L
private const val EXCEEDING_RECENT_COUNT = 25
private const val CONCURRENT_SAVE_COUNT = 16

private val CREDENTIAL_WORDS = listOf("token", "password", "passphrase", "secret")

private fun settingsFileIn(directory: File): Path =
    directory.toPath().resolve("undine").resolve("settings.json")

private fun gatewayFor(settingsFile: Path, now: Long = FIXED_NOW) =
    SettingsGatewayImpl(settingsFile = settingsFile, currentTimeMillis = { now })

private fun backupOf(settingsFile: Path, now: Long = FIXED_NOW): Path =
    settingsFile.resolveSibling("settings.json.corrupt-$now")

private fun newerSchemaBackupOf(settingsFile: Path, now: Long = FIXED_NOW): Path =
    settingsFile.resolveSibling("settings.json.newer-$now")

/** 기본값 전체. 복구 경로는 한 필드가 아니라 [Settings] 전체가 기본값이어야 한다. */
private val DEFAULTS = Settings(
    recentRepositories = emptyList(),
    theme = ThemeMode.SYSTEM,
    window = WindowBounds(width = 1280, height = 800, maximized = false),
)

private fun settingsOf(vararg paths: String) = DEFAULTS.copy(
    recentRepositories = paths.map(::RepositoryPath),
)

/** 이름 있는 이스케이프가 없는 C0 제어문자 표본. JSON 에 날것으로 들어가면 다음 로드가 손상으로 읽는다. */
private const val CONTROL_SAMPLE = "\u0000\u0001\u001F"

/** 가장 최근 백업이 이기는지 보려고 [FIXED_NOW] 보다 하루 앞선 시각을 쓴다. */
private const val OLDER_BACKUP_NOW = FIXED_NOW - 86_400_000L

/** 스키마 2 를 쓰던 앱의 원본. 구버전으로 내려가 저장하면 이 내용이 newer 백업으로 밀려난다. */
private val NEWER_SCHEMA_BACKUP_CONTENT = """
    {
      "schemaVersion": 2,
      "recentRepositories": ["/tmp/before-rollback"],
      "theme": "DARK",
      "window": { "width": 1440, "height": 900, "maximized": false },
      "identityProfiles": [
        {
          "name": "일 이름",
          "email": "work@example.com",
          "signingKeyId": "ABCD1234",
          "defaultAuthentication": "SSH",
          "expectedHost": "github.com"
        }
      ],
      "externalTools": {
        "diffTool": { "executable": "/usr/bin/kdiff3", "arguments": ["--label", "A"] },
        "mergeTool": null
      }
    }
""".trimIndent()

private val BACKED_UP_PROFILE = IdentityProfile(
    name = "일 이름",
    email = "work@example.com",
    signingKeyId = "ABCD1234",
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = "github.com",
)

private fun writeFile(target: Path, content: String) {
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

/**
 * 실제 임시 디렉토리에 파일을 쓰고 읽어 검증한다 — 설정 영속화는 파일 시스템이 계약의 일부다.
 * 시각은 고정 주입해 손상 백업 파일명을 결정적으로 만든다.
 */
class SettingsGatewayImplSpec : FunSpec({

    test("저장한 설정을 새 Gateway 가 그대로 복원한다") {
        val settingsFile = settingsFileIn(tempdir())
        val settings = Settings(
            recentRepositories = listOf(RepositoryPath("/tmp/first"), RepositoryPath("/tmp/second")),
            theme = ThemeMode.DARK,
            window = WindowBounds(width = 1024, height = 768, maximized = true),
        )

        gatewayFor(settingsFile).save(settings)

        Files.exists(settingsFile) shouldBe true
        gatewayFor(settingsFile).load() shouldBe settings
    }

    test("설정 파일이 없으면 기본값을 반환하고 예외를 던지지 않는다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).load() shouldBe Settings(
            recentRepositories = emptyList(),
            theme = ThemeMode.SYSTEM,
            window = WindowBounds(width = 1280, height = 800, maximized = false),
        )
    }

    test("알 수 없는 필드가 포함된 설정 파일도 오류 없이 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 1,
              "recentRepositories": ["/tmp/kept"],
              "theme": "DARK",
              "window": { "width": 1024, "height": 768, "maximized": true, "x": 12 },
              "experimentalPanels": { "left": 0.3, "labels": ["a", "b"] },
              "futureFlag": null
            }
            """.trimIndent(),
        )

        gatewayFor(settingsFile).load() shouldBe Settings(
            recentRepositories = listOf(RepositoryPath("/tmp/kept")),
            theme = ThemeMode.DARK,
            window = WindowBounds(width = 1024, height = 768, maximized = true),
        )
    }

    test("알 수 없는 theme 값은 SYSTEM 으로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "schemaVersion": 1, "theme": "NEON" }""")

        gatewayFor(settingsFile).load().theme shouldBe ThemeMode.SYSTEM
    }

    test("손상된 설정 파일은 corrupt 백업으로 옮겨지고 기본값으로 복구된다") {
        val settingsFile = settingsFileIn(tempdir())
        val corruptContent = "{ this is not json"
        writeFile(settingsFile, corruptContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        Files.exists(settingsFile) shouldBe false
        Files.readString(backupOf(settingsFile)) shouldBe corruptContent
    }

    test("백업 이동이 실패해도 예외 없이 기본값을 반환한다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, "not json at all")
        val blockedBackup = backupOf(settingsFile)
        Files.createDirectories(blockedBackup)
        Files.writeString(blockedBackup.resolve("occupied.txt"), "occupied")

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        Files.exists(settingsFile) shouldBe true
    }

    test("파일의 schemaVersion 이 앱보다 높으면 파일을 건드리지 않고 기본값을 반환한다") {
        val settingsFile = settingsFileIn(tempdir())
        val futureContent = """{ "schemaVersion": 99, "theme": "DARK", "recentRepositories": ["/tmp/x"] }"""
        writeFile(settingsFile, futureContent)

        gatewayFor(settingsFile).load() shouldBe Settings(
            recentRepositories = emptyList(),
            theme = ThemeMode.SYSTEM,
            window = WindowBounds(width = 1280, height = 800, maximized = false),
        )

        Files.readString(settingsFile) shouldBe futureContent
        Files.exists(backupOf(settingsFile)) shouldBe false
    }

    test("최근 저장소가 20개를 넘으면 오래된 항목부터 잘라낸다") {
        val settingsFile = settingsFileIn(tempdir())
        val requested = (1..EXCEEDING_RECENT_COUNT).map { RepositoryPath("/tmp/repo-$it") }

        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = requested,
                theme = ThemeMode.SYSTEM,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        gatewayFor(settingsFile).load().recentRepositories.shouldContainExactly(requested.take(20))
    }

    test("같은 경로가 중복되면 최상단 첫 등장만 남는다") {
        val settingsFile = settingsFileIn(tempdir())
        val reopened = RepositoryPath("/tmp/reopened")

        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = listOf(reopened, RepositoryPath("/tmp/other"), reopened),
                theme = ThemeMode.SYSTEM,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        gatewayFor(settingsFile).load().recentRepositories.shouldContainExactly(
            listOf(reopened, RepositoryPath("/tmp/other")),
        )
    }

    test("이미 목록에 있는 경로를 다시 열면 중복 없이 최상단이 된다") {
        val settingsFile = settingsFileIn(tempdir())
        val newest = RepositoryPath("/tmp/newest")
        val reopened = RepositoryPath("/tmp/reopened")
        val oldest = RepositoryPath("/tmp/oldest")
        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = listOf(newest, reopened, oldest),
                theme = ThemeMode.SYSTEM,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        // 호출부(UND-19·UND-26) 규약: 다시 연 경로를 맨 앞에 붙여 그대로 저장한다.
        val loaded = gatewayFor(settingsFile).load()
        gatewayFor(settingsFile).save(
            loaded.copy(recentRepositories = listOf(reopened) + loaded.recentRepositories),
        )

        gatewayFor(settingsFile).load().recentRepositories.shouldContainExactly(
            listOf(reopened, newest, oldest),
        )
    }

    test("20개가 찬 목록에서 하위 경로를 다시 열어도 상한을 넘지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        val full = (1..MAX_RECENT_REPOSITORIES).map { RepositoryPath("/tmp/repo-$it") }
        val reopened = full.last()
        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = full,
                theme = ThemeMode.SYSTEM,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        val loaded = gatewayFor(settingsFile).load()
        gatewayFor(settingsFile).save(
            loaded.copy(recentRepositories = listOf(reopened) + loaded.recentRepositories),
        )

        gatewayFor(settingsFile).load().recentRepositories.shouldContainExactly(
            listOf(reopened) + full.dropLast(1),
        )
    }

    test("존재하지 않는 최근 저장소 경로도 로드 결과에 그대로 남는다") {
        val workingDirectory = tempdir()
        val settingsFile = settingsFileIn(workingDirectory)
        val missing = RepositoryPath(workingDirectory.toPath().resolve("deleted-repo").toString())

        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = listOf(missing),
                theme = ThemeMode.SYSTEM,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        Files.exists(Path.of(missing.value)) shouldBe false
        gatewayFor(settingsFile).load().recentRepositories.shouldContainExactly(listOf(missing))
    }

    test("설정 JSON 에는 알려진 키만 있고 자격증명 필드가 없다") {
        val settingsFile = settingsFileIn(tempdir())
        gatewayFor(settingsFile).save(
            Settings(
                recentRepositories = listOf(RepositoryPath("/tmp/repo")),
                theme = ThemeMode.LIGHT,
                window = WindowBounds(width = 1280, height = 800, maximized = false),
            ),
        )

        val content = Files.readString(settingsFile)
        val root = JsonParser(content).parseDocument() as Map<*, *>

        root.keys.toList() shouldBe listOf(
            "schemaVersion",
            "recentRepositories",
            "theme",
            "window",
            "identityProfiles",
            "externalTools",
        )
        (root["window"] as Map<*, *>).keys.toList() shouldBe listOf("width", "height", "maximized")
        CREDENTIAL_WORDS.forEach { word -> content.lowercase() shouldNotContain word }
    }

    test("identity 프로필과 외부 도구 설정은 저장 후 같은 값으로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())
        val settings = DEFAULTS.copy(
            identityProfiles = listOf(
                IdentityProfile(
                    name = "일 이름",
                    email = "work@example.com",
                    signingKeyId = "ABCD1234",
                    defaultAuthentication = AuthenticationMethod.SSH,
                    expectedHost = "github.com",
                ),
                IdentityProfile(
                    name = "개인",
                    email = "me@example.com",
                    signingKeyId = null,
                    defaultAuthentication = AuthenticationMethod.HTTPS,
                    expectedHost = null,
                ),
            ),
            externalTools = ExternalToolSettings(
                diffTool = ExternalTool("/usr/bin/kdiff3", listOf("\$LOCAL", "\$REMOTE")),
                mergeTool = ExternalTool("/usr/bin/meld", emptyList()),
            ),
        )

        gatewayFor(settingsFile).save(settings)

        gatewayFor(settingsFile).load() shouldBe settings
    }

    test("프로필이 0건인 설정은 빈 목록으로 복원된다 — null 과 빈 목록을 구분한다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(DEFAULTS.copy(identityProfiles = emptyList()))

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["identityProfiles"] shouldBe emptyList<Any?>()
        gatewayFor(settingsFile).load().identityProfiles.shouldBeEmpty()
    }

    test("직렬화된 프로필에는 서명 키 ID 만 있고 키 본문·패스프레이즈 키가 없다") {
        val settingsFile = settingsFileIn(tempdir())
        gatewayFor(settingsFile).save(
            DEFAULTS.copy(
                identityProfiles = listOf(
                    IdentityProfile(
                        name = "일 이름",
                        email = "work@example.com",
                        signingKeyId = "ABCD1234",
                        defaultAuthentication = AuthenticationMethod.SSH,
                        expectedHost = "github.com",
                    ),
                ),
            ),
        )

        val content = Files.readString(settingsFile)
        val root = JsonParser(content).parseDocument() as Map<*, *>
        val profile = (root["identityProfiles"] as List<*>).single() as Map<*, *>

        profile.keys.toList() shouldBe listOf(
            "name",
            "email",
            "signingKeyId",
            "defaultAuthentication",
            "expectedHost",
        )
        CREDENTIAL_WORDS.forEach { word -> content.lowercase() shouldNotContain word }
    }

    test("새 필드가 없는 기존 설정 파일도 읽히고 기존 값이 보존된다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 1,
              "recentRepositories": ["/tmp/kept"],
              "theme": "DARK",
              "window": { "width": 1024, "height": 768, "maximized": true }
            }
            """.trimIndent(),
        )

        gatewayFor(settingsFile).load() shouldBe Settings(
            recentRepositories = listOf(RepositoryPath("/tmp/kept")),
            theme = ThemeMode.DARK,
            window = WindowBounds(width = 1024, height = 768, maximized = true),
            identityProfiles = emptyList(),
            externalTools = ExternalToolSettings.NONE,
        )
    }

    test("프로필 항목이 깨져 있어도 읽을 수 있는 프로필만 남기고 로드는 실패하지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 2,
              "identityProfiles": [
                { "name": "정상", "email": "ok@example.com", "defaultAuthentication": "SSH" },
                { "email": "이름없음@example.com" },
                "문자열 프로필"
              ],
              "externalTools": { "diffTool": "문자열 도구" }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.identityProfiles shouldBe listOf(
            IdentityProfile(
                name = "정상",
                email = "ok@example.com",
                signingKeyId = null,
                defaultAuthentication = AuthenticationMethod.SSH,
                expectedHost = null,
            ),
        )
        loaded.externalTools shouldBe ExternalToolSettings.NONE
    }

    test("알 수 없는 기본 인증 방식은 HTTPS 로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 2,
              "identityProfiles": [{ "name": "n", "email": "e", "defaultAuthentication": "KERBEROS" }]
            }
            """.trimIndent(),
        )

        gatewayFor(settingsFile).load().identityProfiles.single()
            .defaultAuthentication shouldBe AuthenticationMethod.HTTPS
    }

    test("신버전 스키마 파일은 저장 전에 newer 백업으로 보존된다") {
        val settingsFile = settingsFileIn(tempdir())
        val futureContent = """{ "schemaVersion": 99, "theme": "DARK", "recentRepositories": ["/tmp/x"] }"""
        writeFile(settingsFile, futureContent)

        gatewayFor(settingsFile).save(settingsOf("/tmp/saved"))

        Files.readString(newerSchemaBackupOf(settingsFile)) shouldBe futureContent
        gatewayFor(settingsFile).load() shouldBe settingsOf("/tmp/saved")
    }

    test("신버전 파일을 보존하지 못하면 저장하지 않고 원본을 그대로 둔다") {
        val settingsFile = settingsFileIn(tempdir())
        val futureContent = """{ "schemaVersion": 99, "theme": "DARK" }"""
        writeFile(settingsFile, futureContent)
        // 백업 경로를 비어 있지 않은 디렉토리로 막아 이동을 실패시킨다.
        val blockedBackup = newerSchemaBackupOf(settingsFile)
        Files.createDirectories(blockedBackup)
        Files.writeString(blockedBackup.resolve("occupied.txt"), "occupied")

        shouldThrow<IOException> { gatewayFor(settingsFile).save(settingsOf("/tmp/saved")) }

        Files.readString(settingsFile) shouldBe futureContent
    }

    test("동시 save 는 서로의 임시 파일을 건드리지 않고 모두 성공한다") {
        val settingsFile = settingsFileIn(tempdir())
        val gateway = gatewayFor(settingsFile)
        val requested = (1..CONCURRENT_SAVE_COUNT).map { settingsOf("/tmp/repo-$it") }

        coroutineScope {
            requested.map { settings -> async(Dispatchers.IO) { gateway.save(settings) } }.awaitAll()
        }

        // 마지막 승자가 누구든 온전한 설정이어야 한다 — 섞이거나 잘린 파일이 남으면 안 된다.
        requested shouldContain gateway.load()
        Files.list(settingsFile.parent).use { entries ->
            entries.filter { it.name != settingsFile.name }.count() shouldBe 0L
        }
    }

    test("Int 범위를 넘는 schemaVersion 도 미래 스키마로 보고 저장 시 원본을 보존한다") {
        val settingsFile = settingsFileIn(tempdir())
        val futureContent = """{ "schemaVersion": 2147483648, "theme": "DARK" }"""
        writeFile(settingsFile, futureContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        gatewayFor(settingsFile).save(settingsOf("/tmp/saved"))
        Files.readString(newerSchemaBackupOf(settingsFile)) shouldBe futureContent
    }

    test("Int 범위를 넘는 창 크기는 기본값으로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """{ "schemaVersion": 1, "window": { "width": 4294967296, "height": 800 } }""",
        )

        gatewayFor(settingsFile).load().window shouldBe DEFAULTS.window
    }

    test("부모 없는 상대 경로로도 저장하고 다시 읽는다") {
        val settingsFile = Path.of("undine-relative-settings-test.json")
        try {
            gatewayFor(settingsFile).save(settingsOf("/tmp/relative"))

            Files.exists(settingsFile) shouldBe true
            gatewayFor(settingsFile).load() shouldBe settingsOf("/tmp/relative")
        } finally {
            Files.deleteIfExists(settingsFile)
            Files.list(Path.of(".")).use { entries ->
                entries.filter { it.name.startsWith("undine-relative-settings-test.json") }
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    test("Long 범위를 넘는 schemaVersion 은 해석하지 못하므로 원본을 보존한다") {
        val settingsFile = settingsFileIn(tempdir())
        val futureContent = """{ "schemaVersion": 9223372036854775808, "theme": "DARK" }"""
        writeFile(settingsFile, futureContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        gatewayFor(settingsFile).save(settingsOf("/tmp/saved"))
        Files.readString(newerSchemaBackupOf(settingsFile)) shouldBe futureContent
    }

    test("schemaVersion 이 숫자가 아니어도 원본을 보존한다") {
        val settingsFile = settingsFileIn(tempdir())
        val alienContent = """{ "schemaVersion": "2.0", "theme": "DARK" }"""
        writeFile(settingsFile, alienContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        gatewayFor(settingsFile).save(settingsOf("/tmp/saved"))
        Files.readString(newerSchemaBackupOf(settingsFile)) shouldBe alienContent
    }

    test("schemaVersion 이 없는 최초 형식 파일은 그대로 읽는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(settingsFile, """{ "theme": "DARK", "recentRepositories": ["/tmp/legacy"] }""")

        gatewayFor(settingsFile).load() shouldBe DEFAULTS.copy(
            recentRepositories = listOf(RepositoryPath("/tmp/legacy")),
            theme = ThemeMode.DARK,
        )
    }

    test("schemaVersion 이 명시적 null 이어도 원본을 보존한다") {
        val settingsFile = settingsFileIn(tempdir())
        val alienContent = """{ "schemaVersion": null, "theme": "DARK" }"""
        writeFile(settingsFile, alienContent)

        gatewayFor(settingsFile).load() shouldBe DEFAULTS

        gatewayFor(settingsFile).save(settingsOf("/tmp/saved"))
        Files.readString(newerSchemaBackupOf(settingsFile)) shouldBe alienContent
    }

    test("expectedHost 키가 없는 기존 프로필 파일도 읽히고 호스트만 비워 둔다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 2,
              "identityProfiles": [
                { "name": "일", "email": "work@example.com", "defaultAuthentication": "SSH" }
              ]
            }
            """.trimIndent(),
        )

        gatewayFor(settingsFile).load().identityProfiles shouldBe listOf(
            IdentityProfile(
                name = "일",
                email = "work@example.com",
                signingKeyId = null,
                defaultAuthentication = AuthenticationMethod.SSH,
                expectedHost = null,
            ),
        )
    }

    test("구버전으로 롤백했다 돌아오면 구버전이 담지 못한 필드를 newer 백업에서 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        // 구버전(스키마 1) 앱이 한 번 저장한 뒤의 상태를 그대로 재현한다 — 신버전 원본은 newer 백업으로
        // 밀려나고, 현재 파일은 구버전이 새로 쓴 스키마 1 파일이다.
        writeFile(newerSchemaBackupOf(settingsFile), NEWER_SCHEMA_BACKUP_CONTENT)
        writeFile(
            settingsFile,
            """
            {
              "schemaVersion": 1,
              "recentRepositories": ["/tmp/opened-on-old-version"],
              "theme": "LIGHT",
              "window": { "width": 1024, "height": 768, "maximized": false }
            }
            """.trimIndent(),
        )

        val loaded = gatewayFor(settingsFile).load()

        loaded.identityProfiles shouldBe listOf(BACKED_UP_PROFILE)
        loaded.externalTools shouldBe ExternalToolSettings(
            diffTool = ExternalTool("/usr/bin/kdiff3", listOf("--label", "A")),
            mergeTool = null,
        )
        // 구버전이 아는 필드는 구버전 파일이 이긴다 — 백업은 그보다 오래됐다.
        loaded.recentRepositories shouldBe listOf(RepositoryPath("/tmp/opened-on-old-version"))
        loaded.theme shouldBe ThemeMode.LIGHT
    }

    test("되살린 뒤 저장하면 현재 스키마 파일에 새 필드가 다시 담긴다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), NEWER_SCHEMA_BACKUP_CONTENT)
        writeFile(settingsFile, """{ "schemaVersion": 1, "theme": "LIGHT" }""")
        val gateway = gatewayFor(settingsFile)

        gateway.save(gateway.load())

        val root = JsonParser(Files.readString(settingsFile)).parseDocument() as Map<*, *>
        root["schemaVersion"] shouldBe CURRENT_SCHEMA_VERSION.toLong()
        gatewayFor(settingsFile).load().identityProfiles shouldBe listOf(BACKED_UP_PROFILE)
    }

    test("newer 백업이 여러 개면 가장 최근 것에서 되살린다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile, now = OLDER_BACKUP_NOW), NEWER_SCHEMA_BACKUP_CONTENT)
        writeFile(
            newerSchemaBackupOf(settingsFile, now = FIXED_NOW),
            """
            {
              "schemaVersion": 2,
              "identityProfiles": [
                { "name": "최신", "email": "latest@example.com", "defaultAuthentication": "HTTPS" }
              ]
            }
            """.trimIndent(),
        )
        writeFile(settingsFile, """{ "schemaVersion": 1, "theme": "LIGHT" }""")

        gatewayFor(settingsFile).load().identityProfiles.single().name shouldBe "최신"
    }

    test("현재 스키마 파일을 읽을 때는 newer 백업을 되살리지 않는다") {
        val settingsFile = settingsFileIn(tempdir())
        writeFile(newerSchemaBackupOf(settingsFile), NEWER_SCHEMA_BACKUP_CONTENT)

        gatewayFor(settingsFile).save(DEFAULTS.copy(identityProfiles = emptyList()))

        gatewayFor(settingsFile).load().identityProfiles.shouldBeEmpty()
    }

    test("따옴표·역슬래시·제어문자가 든 값도 저장 후 그대로 복원된다") {
        val settingsFile = settingsFileIn(tempdir())
        val tricky = "따옴표 \"q\" 역슬래시 \\ 탭\t 개행\n 복귀\r 백스페이스\b 폼피드$FORM_FEED$CONTROL_SAMPLE"
        val settings = Settings(
            recentRepositories = listOf(RepositoryPath("/tmp/$tricky")),
            theme = ThemeMode.DARK,
            window = WindowBounds(width = 1024, height = 768, maximized = true),
            identityProfiles = listOf(
                IdentityProfile(
                    name = "이름 $tricky",
                    email = "mail$tricky@example.com",
                    signingKeyId = "key $tricky",
                    defaultAuthentication = AuthenticationMethod.SSH,
                    expectedHost = "host $tricky",
                ),
            ),
            externalTools = ExternalToolSettings(
                diffTool = ExternalTool(executable = "/usr/bin/$tricky", arguments = listOf("--label=$tricky")),
                mergeTool = null,
            ),
        )

        gatewayFor(settingsFile).save(settings)

        gatewayFor(settingsFile).load() shouldBe settings
    }

    test("이름 있는 이스케이프가 없는 제어문자는 유니코드 이스케이프로 적힌다") {
        val settingsFile = settingsFileIn(tempdir())

        gatewayFor(settingsFile).save(settingsOf("/tmp/a${CONTROL_SAMPLE}b"))

        val content = Files.readString(settingsFile)
        content.contains("\\u0000") shouldBe true
        content.contains("\\u001f") shouldBe true
        // 파일에 남은 날것의 C0 제어문자는 포맷용 줄바꿈뿐이어야 한다.
        content.none { it in '\u0000'..'\u001F' && it != '\n' } shouldBe true
    }
})
