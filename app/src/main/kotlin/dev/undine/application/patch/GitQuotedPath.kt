package dev.undine.application.patch

import java.io.ByteArrayOutputStream

private const val QUOTE = '"'
private const val ESCAPE = '\\'
private const val OCTAL_RADIX = 8
private const val OCTAL_DIGITS = 3
private const val MAX_BYTE = 0xFF
private const val MAX_ASCII = 0x7F

/**
 * git 이 인용 경로 안에서 문자 하나로 줄여 쓰는 이스케이프. 나머지(제어문자·비 ASCII 바이트)는
 * 8진 이스케이프로 나온다.
 */
private val CONTROL_ESCAPES: Map<Char, Char> = mapOf(
    'a' to '\u0007',
    'b' to '\b',
    'f' to '\u000C',
    'n' to '\n',
    'r' to '\r',
    't' to '\t',
    'v' to '\u000B',
    ESCAPE to ESCAPE,
    QUOTE to QUOTE,
)

/**
 * git 의 인용을 풀어 원래 경로로 되돌린다. 인용되지 않은 토큰은 그대로가 경로다.
 *
 * 8진 이스케이프는 **UTF-8 바이트**라 바이트로 모아 마지막에 한 번 디코딩한다 — 한 바이트씩 문자로
 * 바꾸면 한글처럼 여러 바이트인 문자가 깨진다.
 */
internal fun unquoteGitPath(token: String): String? = when {
    !token.startsWith(QUOTE) -> token
    token.length < 2 || !token.endsWith(QUOTE) -> null
    else -> unescape(token.substring(1, token.length - 1))
}

private fun unescape(body: String): String? {
    val bytes = ByteArrayOutputStream(body.length)
    var index = 0
    while (index < body.length) {
        index = writeCharAt(body, index, bytes) ?: return null
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/** 한 문자를 바이트로 옮기고 다음 위치를 준다. 우리가 아는 형식이 아니면 `null`. */
private fun writeCharAt(body: String, index: Int, out: ByteArrayOutputStream): Int? = when {
    body[index] == ESCAPE -> writeEscapeAt(body, index + 1, out)
    // 인용 안은 전부 ASCII 다 — 비 ASCII 는 8진 이스케이프로만 나온다.
    body[index].code <= MAX_ASCII -> (index + 1).also { out.write(body[index].code) }
    else -> null
}

private fun writeEscapeAt(body: String, index: Int, out: ByteArrayOutputStream): Int? = when {
    index >= body.length -> null
    CONTROL_ESCAPES.containsKey(body[index]) ->
        (index + 1).also { out.write(CONTROL_ESCAPES.getValue(body[index]).code) }

    else -> writeOctalAt(body, index, out)
}

private fun writeOctalAt(body: String, index: Int, out: ByteArrayOutputStream): Int? =
    body.takeIf { index + OCTAL_DIGITS <= it.length }
        ?.substring(index, index + OCTAL_DIGITS)
        ?.takeIf { digits -> digits.all { digit -> digit in '0'..'7' } }
        ?.toIntOrNull(OCTAL_RADIX)
        ?.takeIf { value -> value <= MAX_BYTE }
        ?.let { value -> (index + OCTAL_DIGITS).also { out.write(value) } }
