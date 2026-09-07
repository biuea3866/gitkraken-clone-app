package dev.undine.infrastructure.git.lfs

private const val UNICODE_ESCAPE_DIGITS = 4
private const val HEX_RADIX = 16
private const val FORM_FEED = '\u000C'

/**
 * `git lfs locks --json` 이 내는 문서를 읽기 위한 **최소 JSON 값** 표현.
 *
 * 새 빌드 의존성을 들이지 않는다는 티켓 제약 때문에 직접 읽는다. 관대하게 만들지 않는다 —
 * 문법에서 조금이라도 벗어나면 [readJson] 이 `null` 을 돌려주고 호출자가 파싱 실패로 보고한다.
 */
internal sealed interface JsonValue {

    data class Text(val value: String) : JsonValue

    data class Members(val entries: Map<String, JsonValue>) : JsonValue

    data class Elements(val values: List<JsonValue>) : JsonValue

    /** 숫자·`true`·`false`·`null`. 잠금 조회가 읽는 값이 아니라 원문만 보관한다. */
    data class Literal(val raw: String) : JsonValue
}

/** [raw] 전체가 JSON 값 하나일 때만 그 값을, 아니면 `null` 을 돌려준다. */
internal fun readJson(raw: String): JsonValue? = JsonReader(raw).readDocument()

private class JsonReader(private val raw: String) {

    private var index = 0

    fun readDocument(): JsonValue? {
        val value = readValue() ?: return null
        skipWhitespace()
        return value.takeIf { index == raw.length }
    }

    private fun readValue(): JsonValue? {
        skipWhitespace()
        val character = raw.getOrNull(index) ?: return null
        return when (character) {
            '{' -> readMembers()
            '[' -> readElements()
            '"' -> readText()?.let(JsonValue::Text)
            else -> readLiteral()
        }
    }

    @Suppress("ReturnCount")
    private fun readMembers(): JsonValue? {
        index += 1
        val entries = mutableMapOf<String, JsonValue>()
        skipWhitespace()
        if (consumeIfNext('}')) return JsonValue.Members(entries)
        while (true) {
            skipWhitespace()
            val name = readText() ?: return null
            skipWhitespace()
            if (!consumeIfNext(':')) return null
            val value = readValue() ?: return null
            entries[name] = value
            skipWhitespace()
            when {
                consumeIfNext(',') -> Unit
                consumeIfNext('}') -> return JsonValue.Members(entries)
                else -> return null
            }
        }
    }

    @Suppress("ReturnCount")
    private fun readElements(): JsonValue? {
        index += 1
        val values = mutableListOf<JsonValue>()
        skipWhitespace()
        if (consumeIfNext(']')) return JsonValue.Elements(values)
        while (true) {
            values += readValue() ?: return null
            skipWhitespace()
            when {
                consumeIfNext(',') -> Unit
                consumeIfNext(']') -> return JsonValue.Elements(values)
                else -> return null
            }
        }
    }

    @Suppress("ReturnCount")
    private fun readText(): String? {
        if (!consumeIfNext('"')) return null
        val text = StringBuilder()
        while (index < raw.length) {
            when (val character = raw[index++]) {
                '"' -> return text.toString()
                '\\' -> text.append(readEscape() ?: return null)
                else -> text.append(character)
            }
        }
        return null
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun readEscape(): Char? {
        val marker = raw.getOrNull(index++) ?: return null
        return when (marker) {
            '"', '\\', '/' -> marker
            'b' -> '\b'
            'f' -> FORM_FEED
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> null
        }
    }

    private fun readUnicodeEscape(): Char? {
        if (index + UNICODE_ESCAPE_DIGITS > raw.length) return null
        val digits = raw.substring(index, index + UNICODE_ESCAPE_DIGITS)
        index += UNICODE_ESCAPE_DIGITS
        return digits.toIntOrNull(HEX_RADIX)?.toChar()
    }

    private fun readLiteral(): JsonValue? {
        val start = index
        while (index < raw.length && (raw[index].isLetterOrDigit() || raw[index] in "+-.")) index += 1
        return raw.substring(start, index).takeIf { it.isNotEmpty() }?.let(JsonValue::Literal)
    }

    private fun consumeIfNext(character: Char): Boolean {
        if (raw.getOrNull(index) != character) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) index += 1
    }
}
