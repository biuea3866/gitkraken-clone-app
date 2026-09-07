package dev.undine.infrastructure.git.lfs

/** LFS 규칙 한 줄이 갖는 고정 속성. `git lfs track` 이 쓰는 것과 같은 형태다. */
private const val LFS_RULE_ATTRIBUTES = "filter=lfs diff=lfs merge=lfs -text"

/** 그 줄이 LFS 규칙인지 가르는 표식. 이것이 없는 줄은 사용자의 다른 규칙이므로 건드리지 않는다. */
private const val LFS_FILTER_ATTRIBUTE = "filter=lfs"

private const val LINE_FEED = "\n"

/**
 * `.gitattributes` **바이트 문자열**을 대상 행만 바꿔 편집한다.
 *
 * 여기서 다루는 `content` 는 파일 바이트를 ISO-8859-1 로 디코딩한 문자열이다 (바이트 ↔ 문자가
 * 1:1 이라 문자열 조작이 곧 바이트 조작이다). 덕분에 UTF-8 이 아닌 바이트가 섞여 있어도 손대지
 * 않은 부분은 **그대로 되돌아간다** — 변환은 [GitAttributesFile] 이 맡는다.
 *
 * **전체를 파싱해 재조립하지 않는다.** 각 행의 위치와 줄끝 길이만 계산해 대상 구간을
 * `removeRange` 로 도려내거나 끝에 덧붙인다. 나머지 바이트는 손대지 않으므로 CRLF·CR·혼합 줄끝과
 * 마지막 개행 유무가 구조적으로 보존된다.
 */
internal object GitAttributesRules {

    /** [pattern] 을 추적하는 규칙 한 줄. */
    fun ruleLine(pattern: String): String = "$pattern $LFS_RULE_ATTRIBUTES"

    /** [content] 안의 LFS 추적 패턴 목록. LFS 표식이 없는 줄은 세지 않는다. */
    fun patternsIn(content: String): List<String> =
        lineSpansOf(content).mapNotNull { span -> lfsPatternOf(content.substring(span.start, span.contentEnd)) }

    /**
     * [pattern] 규칙을 더한 내용. 이미 있으면 [content] 를 그대로 돌려준다.
     *
     * 마지막 줄에 줄끝이 **없으면** 구분자를 앞에 붙이고 뒤에는 붙이지 않는다 — 그래야
     * [without] 가 정확히 같은 구간을 도려내 추가 전 바이트로 되돌아간다.
     */
    fun with(content: String, pattern: String): String {
        if (patternsIn(content).contains(pattern)) return content

        val separator = lastSeparatorOf(content)
        val rule = ruleLine(pattern)
        // 빈 파일은 '줄끝으로 끝난' 쪽과 같게 다룬다 — 앞에 구분자를 붙이면 없던 빈 줄이 생긴다.
        val endsWithLine = content.isEmpty() || endsWithSeparator(content)
        return if (endsWithLine) content + rule + separator else content + separator + rule
    }

    /**
     * [pattern] 규칙을 뺀 내용. 여러 줄이 걸리면 전부 뺀다.
     *
     * 대상이 줄끝 없는 마지막 줄이면 **앞선 줄끝까지** 함께 뺀다 — 남기면 원래 없던 개행이 생긴다.
     */
    fun without(content: String, pattern: String): String {
        val spans = lineSpansOf(content)
        val targets = spans.withIndex()
            .filter { (_, span) -> lfsPatternOf(content.substring(span.start, span.contentEnd)) == pattern }

        // 뒤에서부터 도려낸다 — 앞 구간의 인덱스가 그대로 유효하다.
        return targets.reversed().fold(content) { edited, (index, span) ->
            val start = if (span.terminatorLength > 0) {
                span.start
            } else {
                span.start - (spans.getOrNull(index - 1)?.terminatorLength ?: 0)
            }
            edited.removeRange(start, span.end)
        }
    }

    private fun lfsPatternOf(line: String): String? {
        val tokens = line.split(' ', '\t').filter { token -> token.isNotEmpty() }
        return tokens.firstOrNull()?.takeIf { tokens.contains(LFS_FILTER_ATTRIBUTE) }
    }

    private fun endsWithSeparator(content: String): Boolean =
        content.lastOrNull()?.let { last -> last == '\n' || last == '\r' } == true

    /** 이 파일이 이미 쓰던 구분자. 하나도 없으면(빈 파일·줄끝 없는 한 줄) LF 를 쓴다. */
    private fun lastSeparatorOf(content: String): String {
        val terminated = lineSpansOf(content).lastOrNull { span -> span.terminatorLength > 0 } ?: return LINE_FEED
        return content.substring(terminated.contentEnd, terminated.end)
    }

    private fun lineSpansOf(content: String): List<LineSpan> {
        val spans = mutableListOf<LineSpan>()
        var index = 0
        while (index < content.length) {
            var cursor = index
            while (cursor < content.length && content[cursor] != '\n' && content[cursor] != '\r') cursor += 1
            val terminatorLength = terminatorLengthAt(content, cursor)
            spans += LineSpan(start = index, contentEnd = cursor, terminatorLength = terminatorLength)
            if (terminatorLength == 0) break
            index = cursor + terminatorLength
        }
        return spans
    }

    private fun terminatorLengthAt(content: String, cursor: Int): Int = when {
        cursor >= content.length -> 0
        content[cursor] == '\r' && cursor + 1 < content.length && content[cursor + 1] == '\n' -> 2
        else -> 1
    }
}

/** 한 행의 위치. [start] 부터 [contentEnd] 까지가 본문이고 그 뒤 [terminatorLength] 바이트가 줄끝이다. */
private data class LineSpan(val start: Int, val contentEnd: Int, val terminatorLength: Int) {

    val end: Int get() = contentEnd + terminatorLength
}
