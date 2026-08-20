package dev.undine.infrastructure.settings

private const val NUMBER_TAIL_CHARACTERS = ".eE+-"
private const val UNICODE_ESCAPE_LENGTH = 4
private const val HEX_RADIX = 16

/** JSON 의 폼피드 이스케이프(`\f`). Kotlin 에는 대응 문자 이스케이프가 없다. */
internal const val FORM_FEED = '\u000C'

/** JSON 문법 위반. 메시지는 개발자용이며 파일 내용을 담지 않는다. */
internal class JsonFormatException(message: String) : Exception(message)

/**
 * JSON 토큰 단위 판독기 — 커서 이동과 리터럴(문자열·숫자·`true`·`false`·`null`) 해독만 담당한다.
 * 값의 구조(객체·배열)는 [JsonParser] 가 조립한다.
 */
internal class JsonScanner(private val source: String) {

    private var cursor = 0

    val isExhausted: Boolean get() = cursor >= source.length

    fun skipWhitespace() {
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
    }

    fun peek(): Char =
        if (cursor < source.length) source[cursor] else fail("문서가 예상보다 일찍 끝났습니다")

    fun next(): Char = peek().also { cursor++ }

    fun expect(expected: Char) {
        val actual = next()
        if (actual != expected) fail("'$expected' 를 기대했습니다: '$actual'")
    }

    /** [literal] 을 정확히 소비한다. `true`·`false`·`null` 처리에 쓴다. */
    fun expectLiteral(literal: String) {
        if (!source.startsWith(literal, cursor)) fail("'$literal' 을 기대했습니다")
        cursor += literal.length
    }

    fun readString(): String {
        expect('"')
        val text = StringBuilder()
        while (true) {
            when (val character = next()) {
                '"' -> return text.toString()
                '\\' -> text.append(readEscape())
                else -> text.append(character)
            }
        }
    }

    /** 정수는 [Long], 소수·지수 표기는 [Double] 로 돌려준다. */
    fun readNumber(): Any {
        val start = cursor
        if (peek() == '-') cursor++
        while (!isExhausted) {
            val character = source[cursor]
            if (!character.isDigit() && character !in NUMBER_TAIL_CHARACTERS) break
            cursor++
        }
        val token = source.substring(start, cursor)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("숫자를 읽을 수 없습니다: '$token'")
    }

    fun fail(reason: String): Nothing = throw JsonFormatException("$reason (위치 $cursor)")

    private fun readEscape(): Char = when (val marker = next()) {
        '"', '\\', '/' -> marker
        'b' -> '\b'
        'f' -> FORM_FEED
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> readUnicodeEscape()
        else -> fail("지원하지 않는 이스케이프입니다: '\\$marker'")
    }

    private fun readUnicodeEscape(): Char {
        if (cursor + UNICODE_ESCAPE_LENGTH > source.length) fail("유니코드 이스케이프가 잘렸습니다")
        val digits = source.substring(cursor, cursor + UNICODE_ESCAPE_LENGTH)
        cursor += UNICODE_ESCAPE_LENGTH
        val code = digits.toIntOrNull(HEX_RADIX) ?: fail("유니코드 이스케이프가 16진수가 아닙니다")
        return code.toChar()
    }
}
