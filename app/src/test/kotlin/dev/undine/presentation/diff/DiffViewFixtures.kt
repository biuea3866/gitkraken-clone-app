package dev.undine.presentation.diff

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType

/** 화면 입력 fixture. UND-05 가 내는 `Computed` 계약 모양을 그대로 흉내낸다. */
internal fun contextLine(
    content: String,
    oldLineNumber: Int,
    newLineNumber: Int,
): DiffLine = DiffLine(
    type = DiffLineType.CONTEXT,
    content = content,
    oldLineNumber = oldLineNumber,
    newLineNumber = newLineNumber,
    changedRanges = emptyList(),
)

internal fun deletedLine(
    content: String,
    oldLineNumber: Int,
    changedRanges: List<IntRange> = emptyList(),
): DiffLine = DiffLine(
    type = DiffLineType.DELETED,
    content = content,
    oldLineNumber = oldLineNumber,
    newLineNumber = null,
    changedRanges = changedRanges,
)

internal fun addedLine(
    content: String,
    newLineNumber: Int,
    changedRanges: List<IntRange> = emptyList(),
): DiffLine = DiffLine(
    type = DiffLineType.ADDED,
    content = content,
    oldLineNumber = null,
    newLineNumber = newLineNumber,
    changedRanges = changedRanges,
)

/**
 * 한 줄만 고쳐진 hunk — 문맥 1줄, 삭제 1줄, 추가 1줄, 문맥 1줄.
 * 삭제·추가 쌍의 `changedRanges` 는 `value` 토큰만 가리킨다.
 */
internal fun singleLineEditHunk(): DiffHunk = DiffHunk(
    oldStart = 1,
    oldLineCount = 3,
    newStart = 1,
    newLineCount = 3,
    lines = listOf(
        contextLine("fun main() {", oldLineNumber = 1, newLineNumber = 1),
        deletedLine("    val value = 1", oldLineNumber = 2, changedRanges = listOf(15..15)),
        addedLine("    val value = 2", newLineNumber = 2, changedRanges = listOf(15..15)),
        contextLine("}", oldLineNumber = 3, newLineNumber = 3),
    ),
)

/** 삭제 2줄 뒤 추가 1줄 — 분할 뷰에서 짝이 모자란 쪽이 생기는 경계. */
internal fun unevenHunk(): DiffHunk = DiffHunk(
    oldStart = 10,
    oldLineCount = 3,
    newStart = 10,
    newLineCount = 2,
    lines = listOf(
        contextLine("header", oldLineNumber = 10, newLineNumber = 10),
        deletedLine("first", oldLineNumber = 11),
        deletedLine("second", oldLineNumber = 12),
        addedLine("merged", newLineNumber = 11),
    ),
)

/** 라인 수가 [lineCount] 인 hunk 하나 — 가상 스크롤 검증용. */
internal fun largeHunk(lineCount: Int): DiffHunk = DiffHunk(
    oldStart = 1,
    oldLineCount = lineCount,
    newStart = 1,
    newLineCount = lineCount,
    lines = (1..lineCount).map { number ->
        contextLine("line $number", oldLineNumber = number, newLineNumber = number)
    },
)
