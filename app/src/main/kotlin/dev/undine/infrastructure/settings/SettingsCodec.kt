package dev.undine.infrastructure.settings

import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.ThemeMode
import dev.undine.domain.WindowBounds

/** 이 앱이 쓰는 설정 스키마 버전. 필드를 추가·변경하면 올린다. */
internal const val CURRENT_SCHEMA_VERSION = 1

/** 최근 저장소 보관 상한. 초과분은 `save` 시점에 목록 뒤(= 오래된 쪽)에서 잘린다. */
internal const val MAX_RECENT_REPOSITORIES = 20

private const val DEFAULT_WINDOW_WIDTH = 1280
private const val DEFAULT_WINDOW_HEIGHT = 800

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_RECENT_REPOSITORIES = "recentRepositories"
private const val KEY_THEME = "theme"
private const val KEY_WINDOW = "window"
private const val KEY_WIDTH = "width"
private const val KEY_HEIGHT = "height"
private const val KEY_MAXIMIZED = "maximized"

/** 파일이 없거나 읽을 수 없을 때 시작하는 설정. */
internal val DEFAULT_SETTINGS = Settings(
    recentRepositories = emptyList(),
    theme = ThemeMode.SYSTEM,
    window = WindowBounds(
        width = DEFAULT_WINDOW_WIDTH,
        height = DEFAULT_WINDOW_HEIGHT,
        maximized = false,
    ),
)

/**
 * 설정 파일 해독 결과.
 *
 * 손상·미래 버전을 예외가 아니라 값으로 구분해 호출부가 `when` 으로 빠짐없이 분기한다 —
 * 세 경우의 복구 동작이 서로 다르다(백업 후 기본값 / 그대로 두고 기본값 / 정상 사용).
 */
internal sealed interface SettingsDecodeResult {

    data class Decoded(val settings: Settings) : SettingsDecodeResult

    /** 파일의 스키마 버전이 앱보다 높다. 구버전 앱이 신버전 설정을 망가뜨리지 않게 파일을 건드리지 않는다. */
    data object FromNewerSchema : SettingsDecodeResult

    data class Corrupt(val reason: String) : SettingsDecodeResult
}

internal fun encodeSettings(settings: Settings): String {
    val paths = settings.recentRepositories
        .joinToString(", ") { "\"${escapeJsonString(it.value)}\"" }
    return """
        {
          "$KEY_SCHEMA_VERSION": $CURRENT_SCHEMA_VERSION,
          "$KEY_RECENT_REPOSITORIES": [$paths],
          "$KEY_THEME": "${settings.theme.name}",
          "$KEY_WINDOW": {
            "$KEY_WIDTH": ${settings.window.width},
            "$KEY_HEIGHT": ${settings.window.height},
            "$KEY_MAXIMIZED": ${settings.window.maximized}
          }
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
        // 키가 없으면 스키마 버전을 적기 전의 최초 형식이다 — 우리가 읽을 수 있다.
        schemaVersion == null -> SettingsDecodeResult.Decoded(
            Settings(
                recentRepositories = readRecentRepositories(fields[KEY_RECENT_REPOSITORIES]),
                theme = readTheme(fields[KEY_THEME]),
                window = readWindow(fields[KEY_WINDOW]),
            ),
        )

        // 버전이 있는데 해석할 수 없으면(Long 범위 밖의 수·문자열 등) **우리 것이 아니다.**
        // "모르니 기본값" 으로 처리하면 그 파일을 그대로 덮어써 되돌릴 수 없다.
        schemaVersion !is Long -> SettingsDecodeResult.FromNewerSchema

        // Int 로 좁히지 않는다 — 2^31 이상을 잘라 내면 미래 스키마가 과거로 보인다.
        schemaVersion > CURRENT_SCHEMA_VERSION.toLong() -> SettingsDecodeResult.FromNewerSchema

        else -> SettingsDecodeResult.Decoded(
            Settings(
                recentRepositories = readRecentRepositories(fields[KEY_RECENT_REPOSITORIES]),
                theme = readTheme(fields[KEY_THEME]),
                window = readWindow(fields[KEY_WINDOW]),
            ),
        )
    }
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

/** JSON 문자열 리터럴로 쓸 수 있게 따옴표·역슬래시·제어문자를 이스케이프한다. */
private fun escapeJsonString(raw: String): String = buildString {
    raw.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            FORM_FEED -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
