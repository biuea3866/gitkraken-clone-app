package dev.undine.infrastructure.settings

import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val CORRUPT_SUFFIX = ".corrupt-"
private const val TEMPORARY_SUFFIX = ".tmp"

/**
 * OS 표준 앱 데이터 디렉토리의 `undine/settings.json` 에 설정을 JSON 으로 영속화한다.
 *
 * 읽기는 **절대 예외를 던지지 않는다** — 손상된 설정 파일 때문에 앱이 열리지 않으면 사용자에게
 * 복구 수단이 없다. 파일 부재·파싱 실패·미래 스키마는 모두 기본값 복구로 처리하고, 손상 파일만
 * `settings.json.corrupt-<epochMillis>` 로 옮긴다. 백업 이동 자체가 실패하면 백업을 포기하고
 * 기본값으로 진행한다(앱이 열리는 것이 우선).
 *
 * 최근 저장소 **순서는 정렬하지 않는다** — "목록의 앞이 최신" 규약만 두고 순서 구성은 호출부가 한다.
 * 이미 목록에 있는 저장소를 다시 열면 호출부가 그 경로를 **맨 앞에 붙인** [Settings] 를 [save] 로 넘기고,
 * 여기서 중복 제거하면서 뒤쪽의 옛 위치가 사라져 최상단이 된다. Gateway 에 재오픈 전용 연산을 두지
 * 않는 것은 `SettingsGateway` 계약을 바꾸지 않기 위해서다.
 * 중복 제거와 상한([MAX_RECENT_REPOSITORIES]) 절단은 저장 시점에 여기서 한다.
 *
 * 이 파일에는 **어떤 자격증명도 기록하지 않는다** — 토큰·패스프레이즈는 OS 키체인 소관이다.
 *
 * 쓰기 실패([IOException])는 삼키지 않고 그대로 올린다 — 저장했다고 알린 뒤 사라지는 편이 더 나쁘다.
 *
 * 앱보다 **새로운 스키마** 파일은 저장 전에 백업으로 옮겨 보존한다. 구버전으로 되돌아온 사용자가
 * 설정을 한 번 저장했다고 신버전 전용 값이 복구 수단 없이 사라지면 안 되기 때문이다. 백업에
 * 실패하면 저장하지 않는다 — 원본 보존이 저장보다 우선이다.
 *
 * 같은 인스턴스의 동시 [save] 는 [Mutex] 로 직렬화하고 임시 파일은 호출마다 새로 만든다 —
 * 고정 이름의 임시 파일을 공유하면 한 저장이 다른 저장의 내용을 옮겨 쓴다.
 *
 * @param settingsFile 설정 파일 경로. 현재 플랫폼 기본 경로는 [forCurrentPlatform] 이 정한다.
 * @param currentTimeMillis 백업 파일명(손상·신버전)에 쓰는 시각. 테스트가 고정값을 주입한다.
 */
class SettingsGatewayImpl(
    private val settingsFile: Path,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : SettingsGateway {

    private val serialSave = Mutex()

    override suspend fun load(): Settings = withContext(Dispatchers.IO) { readSettings() }

    override suspend fun save(settings: Settings): Unit = withContext(Dispatchers.IO) {
        serialSave.withLock {
            writeSettings(
                settings.copy(
                    recentRepositories = normalizeRecentRepositories(settings.recentRepositories),
                ),
            )
        }
    }

    private fun readSettings(): Settings {
        val content = readContentOrNull() ?: return DEFAULT_SETTINGS
        return when (val result = decodeSettings(content)) {
            is SettingsDecodeResult.Decoded ->
                if (result.fromOlderSchema) restoreRolledBackFields(result.settings) else result.settings

            SettingsDecodeResult.FromNewerSchema -> {
                logFailure("settings.load.newer_schema", "파일 스키마가 앱보다 새로워 기본값으로 시작합니다")
                DEFAULT_SETTINGS
            }

            is SettingsDecodeResult.Corrupt -> {
                quarantineCorruptFile(result.reason)
                DEFAULT_SETTINGS
            }
        }
    }

    /**
     * 구버전 스키마 파일을 읽었을 때만 탄다 — 구버전이 담지 못한 필드를 그 구버전이 남긴 백업에서 되살린다.
     * 되살릴 것이 없으면 읽은 값을 그대로 쓴다. 복구는 로드를 실패시키지 않는다.
     */
    private fun restoreRolledBackFields(loaded: Settings): Settings {
        val restored = try {
            recoverFieldsFromNewerSchemaBackup(settingsFile, loaded)
        } catch (failure: IOException) {
            logFailure("settings.load.rollback_recovery_failed", failure.toString())
            null
        } ?: return loaded
        logFailure(
            "settings.load.rollback_recovered",
            "구버전 스키마 파일이 담지 못한 필드를 newer 백업에서 되살렸습니다",
        )
        return restored
    }

    private fun readContentOrNull(): String? {
        if (!Files.exists(settingsFile)) return null
        return try {
            Files.readString(settingsFile)
        } catch (failure: IOException) {
            logFailure("settings.load.unreadable", failure.toString())
            null
        }
    }

    private fun quarantineCorruptFile(reason: String) {
        val backup = settingsFile.resolveSibling("${settingsFile.fileName}$CORRUPT_SUFFIX${currentTimeMillis()}")
        try {
            Files.move(settingsFile, backup)
            logFailure("settings.load.corrupt", "$reason — 백업: ${backup.fileName}")
        } catch (failure: IOException) {
            logFailure("settings.load.backup_failed", "$reason — 백업 실패: $failure")
        }
    }

    private fun writeSettings(settings: Settings) {
        settingsFile.parent?.let { Files.createDirectories(it) }
        preserveNewerSchemaFile()
        // 상대 경로로 넘어와도 부모가 있어야 한다 — createTempFile 은 디렉토리가 null 이면 NPE 다.
        val temporaryFile = Files.createTempFile(
            settingsFile.toAbsolutePath().parent,
            settingsFile.fileName.toString(),
            TEMPORARY_SUFFIX,
        )
        try {
            Files.writeString(temporaryFile, encodeSettings(settings))
            Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            Files.deleteIfExists(temporaryFile)
            throw failure
        }
    }

    /**
     * 파일의 스키마가 앱보다 새로우면 `settings.json.newer-<epochMillis>` 로 옮겨 보존한다.
     * 옮기지 못하면 [IOException] 이 그대로 올라가 저장이 중단된다 — 보존하지 못한 신버전 설정을
     * 덮어쓰지 않는다.
     */
    private fun preserveNewerSchemaFile() {
        val content = readContentOrNull() ?: return
        if (decodeSettings(content) !is SettingsDecodeResult.FromNewerSchema) return
        val backup = settingsFile.resolveSibling(
            "${settingsFile.fileName}$NEWER_SCHEMA_SUFFIX${currentTimeMillis()}",
        )
        Files.move(settingsFile, backup)
        logFailure("settings.save.newer_schema_preserved", "신버전 설정을 ${backup.fileName} 로 보존했습니다")
    }

    /** 로깅 수단 도입 전이라 `System.err` 한 줄로 남긴다. 경로는 남기고 파일 내용은 남기지 않는다. */
    private fun logFailure(event: String, detail: String) {
        System.err.println("[undine] $event file=$settingsFile detail=$detail")
    }

    companion object {

        /** 현재 OS 의 표준 설정 경로에 붙는 Gateway. 실제 DI 배선은 UND-26 이 한다. */
        fun forCurrentPlatform(): SettingsGatewayImpl =
            SettingsGatewayImpl(SettingsPaths.currentPlatformSettingsFile())
    }
}
