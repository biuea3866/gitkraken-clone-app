package dev.undine.domain

/**
 * diff 한 줄. 추가된 줄은 [oldLineNumber] 가, 삭제된 줄은 [newLineNumber] 가 없다.
 * [changedRanges] 는 word-level 강조 구간이며 강조가 없으면 빈 목록이다.
 */
data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val changedRanges: List<IntRange>,
)
