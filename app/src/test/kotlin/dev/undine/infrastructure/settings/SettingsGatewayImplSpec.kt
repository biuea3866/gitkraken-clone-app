package dev.undine.infrastructure.settings

import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.assertions.throwables.shouldThrow
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

        root.keys.toList() shouldBe listOf("schemaVersion", "recentRepositories", "theme", "window")
        (root["window"] as Map<*, *>).keys.toList() shouldBe listOf("width", "height", "maximized")
        CREDENTIAL_WORDS.forEach { word -> content.lowercase() shouldNotContain word }
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
})
