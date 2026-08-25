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
 * @return 되살린 값이 있으면 채워진 [Settings], 백업이 없거나 해독할 수 없으면 `null`.
 */
internal fun recoverFieldsFromNewerSchemaBackup(settingsFile: Path, loaded: Settings): Settings? {
    val backup = newestNewerSchemaBackup(settingsFile) ?: return null
    val decoded = decodeSettings(Files.readString(backup)) as? SettingsDecodeResult.Decoded
    val restored = decoded?.settings?.let { backedUp ->
        loaded.copy(identityProfiles = backedUp.identityProfiles, externalTools = backedUp.externalTools)
    }
    return restored?.takeIf { it != loaded }
}

/**
 * 백업이 여러 개면 **가장 최근 것**을 쓴다. 파일명 접미사가 epochMillis 라 수로 비교한다 —
 * 문자열 정렬은 자릿수가 바뀔 때 어긋난다. 수로 읽히지 않는 이름은 우리가 만든 것이 아니므로 건너뛴다.
 */
private fun newestNewerSchemaBackup(settingsFile: Path): Path? {
    val directory = settingsFile.toAbsolutePath().parent ?: return null
    val prefix = "${settingsFile.fileName}$NEWER_SCHEMA_SUFFIX"
    return Files.list(directory).use { entries ->
        entries.toList()
            .filter { it.name.startsWith(prefix) }
            .mapNotNull { path -> path.name.removePrefix(prefix).toLongOrNull()?.let { stamp -> path to stamp } }
            .maxByOrNull { (_, stamp) -> stamp }
            ?.first
    }
}
