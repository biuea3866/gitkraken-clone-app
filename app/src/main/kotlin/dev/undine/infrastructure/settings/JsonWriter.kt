package dev.undine.infrastructure.settings

/**
 * 설정을 손으로 JSON 문자열로 적을 때 쓰는 최소 도구. [JsonParser] 의 반대편이다.
 *
 * 사용자 입력(저장소 경로·프로필 이름·도구 인자)이 그대로 값이 되므로, 따옴표나 역슬래시가 든
 * 값을 이스케이프하지 않으면 **다음 로드에서 파일 전체가 손상으로 판정**된다.
 */
internal fun jsonString(raw: String): String = "\"${escapeJsonString(raw)}\""

/** JSON 이 날것으로 허용하지 않는 C0 제어문자 구간. 이름 있는 이스케이프가 없는 값은 `\uXXXX` 로 적는다. */
private val CONTROL_CHARACTERS = '\u0000'..'\u001F'
private const val UNICODE_ESCAPE_FORMAT = "\\u%04x"

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
            // 나머지 C0 제어문자는 JSON 문자열에 날것으로 들어갈 수 없다 — 그대로 적으면 다음 로드가 손상으로 읽는다.
            in CONTROL_CHARACTERS -> append(UNICODE_ESCAPE_FORMAT.format(character.code))
            else -> append(character)
        }
    }
}
