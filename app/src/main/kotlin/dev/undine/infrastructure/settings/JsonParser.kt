package dev.undine.infrastructure.settings

private const val TRUE_LITERAL = "true"
private const val FALSE_LITERAL = "false"
private const val NULL_LITERAL = "null"

/**
 * 설정 파일 읽기에 필요한 범위만 지원하는 최소 JSON 파서.
 *
 * `kotlinx.serialization` 이 버전 카탈로그에 없고 의존성 추가는 이 티켓 소유 밖이라 수기로 구현했다.
 * 값은 `Map`·`List`·`String`·`Long`·`Double`·`Boolean`·`null` 트리로 돌려주며,
 * **모르는 키도 트리에 그대로 남긴다** — 무시하는 판단은 호출부([decodeSettings])가 한다.
 * 토큰 판독은 [JsonScanner] 가 맡고 이 클래스는 구조 조립만 한다.
 */
internal class JsonParser(source: String) {

    private val scanner = JsonScanner(source)

    fun parseDocument(): Any? {
        val value = parseValue()
        scanner.skipWhitespace()
        if (!scanner.isExhausted) scanner.fail("문서 끝에 잉여 문자가 있습니다")
        return value
    }

    private fun parseValue(): Any? {
        scanner.skipWhitespace()
        return when (val character = scanner.peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> scanner.readString()
            't' -> true.also { scanner.expectLiteral(TRUE_LITERAL) }
            'f' -> false.also { scanner.expectLiteral(FALSE_LITERAL) }
            'n' -> null.also { scanner.expectLiteral(NULL_LITERAL) }
            '-' -> scanner.readNumber()
            else -> if (character.isDigit()) scanner.readNumber() else scanner.fail("값을 읽을 수 없습니다")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        scanner.expect('{')
        val entries = LinkedHashMap<String, Any?>()
        scanner.skipWhitespace()
        if (scanner.peek() == '}') {
            scanner.next()
            return entries
        }
        while (true) {
            scanner.skipWhitespace()
            val key = scanner.readString()
            scanner.skipWhitespace()
            scanner.expect(':')
            entries[key] = parseValue()
            scanner.skipWhitespace()
            when (val separator = scanner.next()) {
                ',' -> Unit
                '}' -> return entries
                else -> scanner.fail("객체에서 ',' 또는 '}' 를 기대했습니다: '$separator'")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        scanner.expect('[')
        val elements = mutableListOf<Any?>()
        scanner.skipWhitespace()
        if (scanner.peek() == ']') {
            scanner.next()
            return elements
        }
        while (true) {
            elements += parseValue()
            scanner.skipWhitespace()
            when (val separator = scanner.next()) {
                ',' -> Unit
                ']' -> return elements
                else -> scanner.fail("배열에서 ',' 또는 ']' 를 기대했습니다: '$separator'")
            }
        }
    }
}
