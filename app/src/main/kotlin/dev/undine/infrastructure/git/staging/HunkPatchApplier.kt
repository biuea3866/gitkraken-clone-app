package dev.undine.infrastructure.git.staging

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLineType
import dev.undine.domain.UndineException

private const val MISMATCH_DETAIL = "스테이징할 변경이 현재 인덱스 내용과 맞지 않습니다"

/**
 * 선택한 hunk 만 적용한 파일 내용을 만든다. 워킹트리 파일은 건드리지 않고,
 * 인덱스에 넣을 blob 내용만 계산하는 순수 함수다.
 *
 * 해석 규약은 unified diff 그대로다.
 * - `oldLineCount > 0` 이면 [DiffHunk.oldStart] 는 hunk 가 덮는 **첫 줄 번호(1부터)** 다.
 * - `oldLineCount == 0` 이면 순수 삽입이고 [DiffHunk.oldStart] 는 **삽입 지점 직전 줄 번호**다
 *   (빈 파일이면 0).
 * - [dev.undine.domain.DiffLine.content] 는 `+`·`-` 접두를 뺀 줄 본문이며 개행을 포함하지 않는다.
 * - 줄 대응은 hunk 안의 **순서**로 판단한다 — `oldLineNumber`·`newLineNumber` 는 쓰지 않는다.
 *
 * 원문의 끝 개행 유무를 그대로 유지한다. 원문이 비어 있으면 끝 개행을 붙인다.
 */
internal object HunkPatchApplier {

    fun apply(base: String, hunks: List<DiffHunk>): String {
        if (hunks.isEmpty()) return base
        val baseLines = splitLines(base)
        val patched = mutableListOf<String>()
        var cursor = 0
        hunks.sortedBy { hunk -> startIndexOf(hunk) }.forEach { hunk ->
            val start = startIndexOf(hunk)
            if (start < cursor) throw UndineException.StateViolation(MISMATCH_DETAIL)
            patched += baseLines.subList(cursor.coerceAtMost(baseLines.size), start.coerceAtMost(baseLines.size))
            cursor = applyHunk(hunk, baseLines, start, patched)
        }
        patched += baseLines.drop(cursor)
        return joinLines(patched, base.isEmpty() || base.endsWith("\n"))
    }

    /**
     * hunk 한 개를 [patched] 에 이어 붙이고 원문에서 소비한 다음 위치를 돌려준다.
     * context·deleted 줄은 원문과 내용이 같아야 한다 — 다르면 화면이 낡은 diff 를 들고 있는 것이다.
     */
    private fun applyHunk(hunk: DiffHunk, baseLines: List<String>, start: Int, patched: MutableList<String>): Int {
        var cursor = start
        hunk.lines.forEach { line ->
            when (line.type) {
                DiffLineType.ADDED -> patched += line.content
                DiffLineType.CONTEXT -> {
                    requireBaseLine(baseLines, cursor, line.content)
                    patched += line.content
                    cursor += 1
                }
                DiffLineType.DELETED -> {
                    requireBaseLine(baseLines, cursor, line.content)
                    cursor += 1
                }
            }
        }
        return cursor
    }

    private fun requireBaseLine(baseLines: List<String>, index: Int, expected: String) {
        if (index >= baseLines.size || baseLines[index] != expected) {
            throw UndineException.StateViolation(MISMATCH_DETAIL)
        }
    }

    private fun startIndexOf(hunk: DiffHunk): Int =
        if (hunk.oldLineCount == 0) hunk.oldStart else (hunk.oldStart - 1).coerceAtLeast(0)

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

    private fun joinLines(lines: List<String>, endsWithNewLine: Boolean): String {
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        return if (endsWithNewLine) joined + "\n" else joined
    }
}
