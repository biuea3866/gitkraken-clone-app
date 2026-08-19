package dev.undine.domain

/** diff hunk 1개 — `@@ -oldStart,oldLineCount +newStart,newLineCount @@` 에 대응한다. */
data class DiffHunk(
    val oldStart: Int,
    val oldLineCount: Int,
    val newStart: Int,
    val newLineCount: Int,
    val lines: List<DiffLine>,
)
