package dev.undine.infrastructure.settings

import dev.undine.domain.Settings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** 앱보다 새로운 스키마 파일을 보존할 때 붙이는 접미사. 뒤에 epochMillis 가 붙는다. */
internal const val NEWER_SCHEMA_SUFFIX = ".newer-"

/**
 * 다운그레이드 후 되돌아왔을 때, 구버전이 보존해 둔 백업에서 **구버전이 표현할 수 없던 필드만** 되살린다.
 *
 * 이 경로가 필요한 이유: 스키마 2 설정을 쓰던 사용자가 스키마 1 앱으로 내려가 한 번 저장하면
 * [SettingsGatewayImpl] 이 원본을 `settings.json$NEWER_SCHEMA_SUFFIX<epochMillis>` 로 옮기고
 * 스키마 1 파일을 새로 쓴다. 다시 올라온 앱이 그 스키마 1 파일만 읽으면 identity 프로필·외부 도구
 * 설정은 백업에만 남아 앱에서는 사라진 것처럼 보인다.
 *
 * **구버전이 아는 필드는 손대지 않는다** — 그 값은 사용자가 구버전에서 실제로 고쳤을 수 있고,
 * 백업은 그보다 오래됐다. 되살리는 것은 구버전 스키마가 담을 수 없어 **잃을 수밖에 없던 필드**뿐이며,
 * 이 판정은 호출부가 [SettingsDecodeResult.Decoded.fromOlderSchema] 로 내린다.
 *
 * 백업은 지우지 않는다 — 보존이 목적이고, 다음 저장이 현재 스키마 파일을 쓰면 이 경로는 더 이상 타지 않는다.
 *
 * 읽기 실패는 삼키지 않고 [IOException] 으로 올린다 — 복구 실패 사유를 남길지는 호출부가 정한다.
 *
 * @param loadedSchemaVersion 현재 설정 파일이 적힌 스키마 버전. **그 버전이 담을 수 없던 필드만**
 * 되살리는 기준이다 — 스키마 2 파일을 읽을 때 identity 프로필까지 백업 값으로 덮으면 사용자가
 * 구버전에서 실제로 고친 값이 조용히 되돌아간다.
 * @return 되살린 값이 있으면 채워진 [Settings], 백업이 없거나 해독할 수 없으면 `null`.
 */
internal fun recoverFieldsFromNewerSchemaBackup(
    settingsFile: Path,
    loaded: Settings,
    loadedSchemaVersion: Int,
): Settings? {
    val backups = decodedBackupsNewestFirst(settingsFile)
    if (backups.isEmpty()) return null
    return restoreFieldsMissingFrom(loaded, backups, loadedSchemaVersion).takeIf { it != loaded }
}

/**
 * 버전마다 그 스키마가 표현할 수 있던 필드가 다르다 — 낮은 버전일수록 되살릴 것이 많다.
 * 새 필드를 더하는 티켓은 자기 버전 분기를 여기에 한 줄 추가한다.
 */
private fun restoreFieldsMissingFrom(
    loaded: Settings,
    backupsNewestFirst: List<SettingsDecodeResult.Decoded>,
    loadedSchemaVersion: Int,
): Settings {
    var restored = loaded
    if (loadedSchemaVersion < IDENTITY_AND_TOOLS_SCHEMA_VERSION) {
        newestExpressing(backupsNewestFirst, IDENTITY_AND_TOOLS_SCHEMA_VERSION)?.let { backedUp ->
            restored = restored.copy(
                identityProfiles = backedUp.identityProfiles,
                externalTools = backedUp.externalTools,
            )
        }
    }
    if (loadedSchemaVersion < PREFERENCE_SCHEMA_VERSION) {
        newestExpressing(backupsNewestFirst, PREFERENCE_SCHEMA_VERSION)?.let { backedUp ->
            restored = restored.copy(
                language = backedUp.language,
                reopenLastRepository = backedUp.reopenLastRepository,
                confirmDestructiveActions = backedUp.confirmDestructiveActions,
                openTabs = backedUp.openTabs,
                activeTabIndex = backedUp.activeTabIndex,
                updateCheck = backedUp.updateCheck,
            )
        }
    }
    if (loadedSchemaVersion < SHORTCUT_OVERRIDES_SCHEMA_VERSION) {
        newestExpressing(backupsNewestFirst, SHORTCUT_OVERRIDES_SCHEMA_VERSION)?.let { backedUp ->
            restored = restored.copy(shortcutOverrides = backedUp.shortcutOverrides)
        }
    }
    return restored
}

/**
 * 필드 묶음마다 **그 필드를 담을 수 있던 가장 최근 백업**을 고른다.
 *
 * 최신 백업 하나만 보면 연속 다운그레이드(3 → 2 → 1)에서 값을 잃는다: 그때 최신 백업은 스키마 2
 * 파일이라 wave 8 필드를 애초에 담지 못하고, 거기서 읽은 기본값으로 덮으면 스키마 3 백업에 남아
 * 있던 사용자 값이 다음 저장 때 영구히 사라진다. 담을 수 있던 버전으로 거르면 묶음별로 스키마 2
 * 백업의 identity 값과 스키마 3 백업의 wave 8 값을 모두 살린다.
 */
private fun newestExpressing(
    backupsNewestFirst: List<SettingsDecodeResult.Decoded>,
    requiredSchemaVersion: Int,
): Settings? = backupsNewestFirst.firstOrNull { it.schemaVersion >= requiredSchemaVersion }?.settings

/**
 * 백업을 **최근 것부터** 해독해 돌려준다.
 *
 * 해독할 수 없는 백업(손상·앱보다 새로운 스키마)은 되살릴 근거가 없으므로 목록에서 빠진다 —
 * 그 자리에서 멈추면 더 오래된 쪽에 남아 있는 값까지 잃는다.
 */
private fun decodedBackupsNewestFirst(settingsFile: Path): List<SettingsDecodeResult.Decoded> =
    newerSchemaBackupsNewestFirst(settingsFile)
        .mapNotNull { backup -> decodeSettings(Files.readString(backup)) as? SettingsDecodeResult.Decoded }

/**
 * 파일명 접미사가 epochMillis 라 수로 비교한다 — 문자열 정렬은 자릿수가 바뀔 때 어긋난다.
 * 수로 읽히지 않는 이름은 우리가 만든 것이 아니므로 건너뛴다.
 */
private fun newerSchemaBackupsNewestFirst(settingsFile: Path): List<Path> {
    val directory = settingsFile.toAbsolutePath().parent ?: return emptyList()
    val prefix = "${settingsFile.fileName}$NEWER_SCHEMA_SUFFIX"
    return Files.list(directory).use { entries ->
        entries.toList()
            .filter { it.name.startsWith(prefix) }
            .mapNotNull { path -> path.name.removePrefix(prefix).toLongOrNull()?.let { stamp -> path to stamp } }
            .sortedByDescending { (_, stamp) -> stamp }
            .map { (path, _) -> path }
    }
}
