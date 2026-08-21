package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText

/** 라인 edit 목록을 `@@` 단위 hunk 로 묶는다. 컨텍스트는 git 기본값과 같은 3줄이다. */
internal object HunkBuilder {

    private const val CONTEXT_LINES = 3

    fun build(oldText: RawText, newText: RawText, edits: EditList): List<DiffHunk> =
        groupEdits(edits).map { group -> hunkOf(oldText, newText, group) }

    /** 컨텍스트가 겹칠 만큼 가까운 edit 은 같은 hunk 로 합친다. */
    private fun groupEdits(edits: EditList): List<List<Edit>> {
        val groups = mutableListOf<MutableList<Edit>>()
        for (edit in edits) {
            val current = groups.lastOrNull()
            if (current != null && edit.beginA - current.last().endA <= CONTEXT_LINES * 2) {
                current += edit
            } else {
                groups += mutableListOf(edit)
            }
        }
        return groups
    }

    private fun hunkOf(oldText: RawText, newText: RawText, group: List<Edit>): DiffHunk {
        val oldFrom = maxOf(0, group.first().beginA - CONTEXT_LINES)
        val newFrom = maxOf(0, group.first().beginB - CONTEXT_LINES)
        val lines = mutableListOf<DiffLine>()
        var oldLine = oldFrom
        var newLine = newFrom
        for (edit in group) {
            while (oldLine < edit.beginA) {
                lines += contextLine(oldText, oldLine++, newLine++)
            }
            while (oldLine < edit.endA) {
                lines += deletedLine(oldText, oldLine++)
            }
            while (newLine < edit.endB) {
                lines += addedLine(newText, newLine++)
            }
        }
        val trailing = minOf(
            minOf(oldText.size(), group.last().endA + CONTEXT_LINES) - oldLine,
            minOf(newText.size(), group.last().endB + CONTEXT_LINES) - newLine,
        )
        repeat(trailing) { lines += contextLine(oldText, oldLine++, newLine++) }
        return DiffHunk(
            oldStart = startOf(oldFrom, oldLine - oldFrom),
            oldLineCount = oldLine - oldFrom,
            newStart = startOf(newFrom, newLine - newFrom),
            newLineCount = newLine - newFrom,
            lines = WordDiffCalculator.withChangedRanges(lines),
        )
    }

    private fun contextLine(oldText: RawText, oldIndex: Int, newIndex: Int): DiffLine =
        DiffLine(DiffLineType.CONTEXT, oldText.getString(oldIndex), oldIndex + 1, newIndex + 1, emptyList())

    private fun deletedLine(oldText: RawText, oldIndex: Int): DiffLine =
        DiffLine(DiffLineType.DELETED, oldText.getString(oldIndex), oldIndex + 1, null, emptyList())

    private fun addedLine(newText: RawText, newIndex: Int): DiffLine =
        DiffLine(DiffLineType.ADDED, newText.getString(newIndex), null, newIndex + 1, emptyList())

    /** 한쪽이 0줄인 hunk 는 git 관례대로 시작 줄을 0 으로 적는다. */
    private fun startOf(from: Int, count: Int): Int = if (count == 0) 0 else from + 1
}
