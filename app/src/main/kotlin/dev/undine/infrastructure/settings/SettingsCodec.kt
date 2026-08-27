package dev.undine.infrastructure.settings

import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds

/** 이 앱이 쓰는 설정 스키마 버전. 필드를 추가·변경하면 올린다. */
internal const val CURRENT_SCHEMA_VERSION = 4

/**
 * `schemaVersion` 키 자체가 없던 최초 형식. 그 파일은 이후 버전이 더한 어떤 필드도 담지 못한다.
 *
 * 버전을 수로 다루는 것은 롤백 복구가 **어느 필드를 되살려야 하는지**를 이 값으로 가르기 때문이다
 * ([recoverFieldsFromNewerSchemaBackup]).
 */
internal const val INITIAL_SCHEMA_VERSION = 1

/** identity 프로필·외부 도구 설정이 들어온 버전. 이보다 낮은 파일은 그 두 필드를 담지 못한다. */
internal const val IDENTITY_AND_TOOLS_SCHEMA_VERSION = 2

/** 언어·시작 동작·확인 대화상자·탭 세션·업데이트 확인이 들어온 버전(UND-63). */
internal const val PREFERENCE_SCHEMA_VERSION = 3

/** 단축키 오버라이드 매핑이 들어온 버전(UND-40). 이보다 낮은 파일은 그 매핑을 담지 못한다. */
internal const val SHORTCUT_OVERRIDES_SCHEMA_VERSION = 4

/** 최근 저장소 보관 상한. 초과분은 `save` 시점에 목록 뒤(= 오래된 쪽)에서 잘린다. */
internal const val MAX_RECENT_REPOSITORIES = 20

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_RECENT_REPOSITORIES = "recentRepositories"
private const val KEY_THEME = "theme"
private const val KEY_WINDOW = "window"
private const val KEY_WIDTH = "width"
private const val KEY_HEIGHT = "height"
private const val KEY_MAXIMIZED = "maximized"
private const val KEY_IDENTITY_PROFILES = "identityProfiles"
private const val KEY_EXTERNAL_TOOLS = "externalTools"

/** 파일이 없거나 읽을 수 없을 때 시작하는 설정. 기본값의 정의는 domain 이 소유한다. */
internal val DEFAULT_SETTINGS: Settings = Settings.DEFAULTS

/**
 * 설정 파일 해독 결과.
 *
 * 손상·미래 버전을 예외가 아니라 값으로 구분해 호출부가 `when` 으로 빠짐없이 분기한다 —
 * 세 경우의 복구 동작이 서로 다르다(백업 후 기본값 / 그대로 두고 기본값 / 정상 사용).
 */
internal sealed interface SettingsDecodeResult {

    /**
     * @property schemaVersion 파일이 적힌 스키마 버전. 버전을 적기 전의 최초 형식은
     * [INITIAL_SCHEMA_VERSION] 으로 읽는다. [CURRENT_SCHEMA_VERSION] 보다 높은 값은 여기 오지 않는다
     * ([FromNewerSchema] 로 갈린다).
     *
     * 불리언이 아니라 **버전 수**를 남기는 이유: 그 파일이 어느 필드까지 표현할 수 있었는지가
     * 버전마다 다르다. 롤백 복구는 "그 버전이 담을 수 없던 필드" 만 되살려야 하고, 그 판단에
     * 버전이 필요하다 ([recoverFieldsFromNewerSchemaBackup]).
     */
    data class Decoded(val settings: Settings, val schemaVersion: Int) : SettingsDecodeResult {

        /**
         * 파일이 [CURRENT_SCHEMA_VERSION] 보다 **낮은** 스키마로 적혔다. 그 파일은 이후 버전이 더한
         * 필드를 애초에 표현할 수 없으므로, 그 필드가 비어 있는 것은 "사용자가 비웠다" 가 아니라
         * "적을 수 없었다" 다 — 롤백 복구가 이 구분에 기댄다.
         */
        val fromOlderSchema: Boolean get() = schemaVersion < CURRENT_SCHEMA_VERSION
    }

    /** 파일의 스키마 버전이 앱보다 높다. 구버전 앱이 신버전 설정을 망가뜨리지 않게 파일을 건드리지 않는다. */
    data object FromNewerSchema : SettingsDecodeResult

    data class Corrupt(val reason: String) : SettingsDecodeResult
}

internal fun encodeSettings(settings: Settings): String {
    val paths = settings.recentRepositories.joinToString(", ") { jsonString(it.value) }
    val profiles = settings.identityProfiles.joinToString(", ", transform = ::encodeIdentityProfile)
    return """
        {
          "$KEY_SCHEMA_VERSION": $CURRENT_SCHEMA_VERSION,
          "$KEY_RECENT_REPOSITORIES": [$paths],
          "$KEY_THEME": "${settings.theme.name}",
          "$KEY_WINDOW": {
            "$KEY_WIDTH": ${settings.window.width},
            "$KEY_HEIGHT": ${settings.window.height},
            "$KEY_MAXIMIZED": ${settings.window.maximized}
          },
          "$KEY_IDENTITY_PROFILES": [$profiles],
          "$KEY_EXTERNAL_TOOLS": {
            "$KEY_DIFF_TOOL": ${encodeExternalTool(settings.externalTools.diffTool)},
            "$KEY_MERGE_TOOL": ${encodeExternalTool(settings.externalTools.mergeTool)}
          },
          "$KEY_LANGUAGE": ${encodeLanguage(settings.language)},
          "$KEY_REOPEN_LAST_REPOSITORY": ${settings.reopenLastRepository},
          "$KEY_CONFIRM_DESTRUCTIVE_ACTIONS": ${settings.confirmDestructiveActions},
          "$KEY_OPEN_TABS": [${encodeOpenTabs(settings.openTabs)}],
          "$KEY_ACTIVE_TAB_INDEX": ${settings.activeTabIndex},
          "$KEY_UPDATE_CHECK": ${encodeUpdateCheck(settings.updateCheck)},
          "$KEY_SHORTCUT_OVERRIDES": ${encodeShortcutOverrides(settings.shortcutOverrides)}
        }
    """.trimIndent() + "\n"
}

/**
 * 알 수 없는 키는 무시하고, 알려진 키가 없거나 타입이 어긋나면 그 필드만 기본값으로 읽는다 —
 * 엄격 파싱은 하위·상위 호환을 깨뜨린다.
 */
internal fun decodeSettings(text: String): SettingsDecodeResult {
    val document = try {
        JsonParser(text).parseDocument()
    } catch (failure: JsonFormatException) {
        return SettingsDecodeResult.Corrupt(failure.message ?: "JSON 을 읽을 수 없습니다")
    }
    return decodeFields(document)
}

private fun decodeFields(document: Any?): SettingsDecodeResult {
    val fields = document as? Map<*, *>
        ?: return SettingsDecodeResult.Corrupt("최상위 값이 JSON 객체가 아닙니다")
    val schemaVersion = fields[KEY_SCHEMA_VERSION]

    return when {
        // 키 자체가 없어야 스키마 버전을 적기 전의 최초 형식이다 — 우리가 읽을 수 있다.
        // 값이 명시적 null 인 것은 "버전을 적었는데 우리가 모르는 형태" 라 아래 분기로 내려간다.
        !fields.containsKey(KEY_SCHEMA_VERSION) ->
            SettingsDecodeResult.Decoded(readSettings(fields), schemaVersion = INITIAL_SCHEMA_VERSION)

        // 버전이 있는데 해석할 수 없으면(Long 범위 밖의 수·문자열 등) **우리 것이 아니다.**
        // "모르니 기본값" 으로 처리하면 그 파일을 그대로 덮어써 되돌릴 수 없다.
        schemaVersion !is Long -> SettingsDecodeResult.FromNewerSchema

        // Int 로 좁히지 않는다 — 2^31 이상을 잘라 내면 미래 스키마가 과거로 보인다.
        schemaVersion > CURRENT_SCHEMA_VERSION.toLong() -> SettingsDecodeResult.FromNewerSchema

        // 여기 오는 값은 CURRENT_SCHEMA_VERSION 이하다. 1 미만은 있을 수 없는 값이라 최초 형식으로
        // 본다 — 그렇게 보면 롤백 복구가 "아무것도 담지 못한 파일" 로 다뤄 데이터를 잃지 않는다.
        else -> SettingsDecodeResult.Decoded(
            settings = readSettings(fields),
            schemaVersion = schemaVersion.coerceAtLeast(INITIAL_SCHEMA_VERSION.toLong()).toInt(),
        )
    }
}

/**
 * 알려진 키만 읽는다. 새 키(스키마 2 의 [KEY_IDENTITY_PROFILES]·[KEY_EXTERNAL_TOOLS], 스키마 3 의
 * [KEY_LANGUAGE] 이하)가 없는 구버전 파일은 그 필드만 기본값이 되고 나머지 값은 그대로 보존된다.
 */
private fun readSettings(fields: Map<*, *>): Settings {
    val openTabs = readOpenTabs(fields[KEY_OPEN_TABS])
    return Settings(
        recentRepositories = readRecentRepositories(fields[KEY_RECENT_REPOSITORIES]),
        theme = readTheme(fields[KEY_THEME]),
        window = readWindow(fields[KEY_WINDOW]),
        identityProfiles = readIdentityProfiles(fields[KEY_IDENTITY_PROFILES]),
        externalTools = readExternalTools(fields[KEY_EXTERNAL_TOOLS]),
        language = readLanguage(fields[KEY_LANGUAGE]),
        reopenLastRepository = readBooleanOr(
            fields[KEY_REOPEN_LAST_REPOSITORY],
            DEFAULT_SETTINGS.reopenLastRepository,
        ),
        confirmDestructiveActions = readBooleanOr(
            fields[KEY_CONFIRM_DESTRUCTIVE_ACTIONS],
            DEFAULT_SETTINGS.confirmDestructiveActions,
        ),
        openTabs = openTabs,
        activeTabIndex = readActiveTabIndex(fields[KEY_ACTIVE_TAB_INDEX], openTabs),
        updateCheck = readUpdateCheck(fields[KEY_UPDATE_CHECK]),
        shortcutOverrides = readShortcutOverrides(fields[KEY_SHORTCUT_OVERRIDES]),
    )
}

/**
 * 목록의 앞이 최신이라는 규약만 지킨다 — 정렬은 호출부 몫이고, 중복 제거와 상한 절단은 여기서 한다.
 *
 * 이미 목록에 있는 저장소를 다시 열었을 때 최상단으로 올리는 것은 **호출부(UND-19·UND-26)** 가
 * 그 경로를 맨 앞에 붙인 [Settings] 를 넘겨 수행한다. [distinct] 가 첫 등장을 남기므로 뒤쪽의
 * 옛 위치가 사라지고 맨 앞이 남는다.
 */
internal fun normalizeRecentRepositories(paths: List<RepositoryPath>): List<RepositoryPath> =
    paths.distinct().take(MAX_RECENT_REPOSITORIES)

private fun readRecentRepositories(value: Any?): List<RepositoryPath> =
    (value as? List<*>)
        ?.filterIsInstance<String>()
        ?.map(::RepositoryPath)
        ?: DEFAULT_SETTINGS.recentRepositories

private fun readTheme(value: Any?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == value } ?: DEFAULT_SETTINGS.theme

private fun readWindow(value: Any?): WindowBounds {
    val fields = value as? Map<*, *> ?: return DEFAULT_SETTINGS.window
    return WindowBounds(
        width = fields.readInt(KEY_WIDTH) ?: DEFAULT_SETTINGS.window.width,
        height = fields.readInt(KEY_HEIGHT) ?: DEFAULT_SETTINGS.window.height,
        maximized = fields[KEY_MAXIMIZED] as? Boolean ?: DEFAULT_SETTINGS.window.maximized,
    )
}

private fun Map<*, *>.readInt(key: String): Int? = readLong(key)?.let { value ->
    // Int 범위를 벗어난 창 크기는 "값 없음" 으로 본다 — 잘라 내면 음수 크기가 된다.
    if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
}

private fun Map<*, *>.readLong(key: String): Long? = this[key] as? Long

