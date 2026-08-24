package dev.undine.presentation.diff

import androidx.compose.runtime.Immutable
import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import dev.undine.presentation.design.DiffChangeMark

private const val TAB = '\t'
private const val SPACE = ' '

/**
 * 한 열에 그리는 diff 한 줄.
 *
 * @property text 탭을 편 본문. 표시용 변형은 여기서 끝나고 화면은 그대로 그린다.
 * @property changedRanges word-level 강조 구간. **gateway 가 준 `DiffLine.changedRanges` 를 옮긴 값**이며
 *   presentation 이 무엇이 바뀌었는지 다시 계산하지 않는다 — 탭을 편 만큼 위치만 따라 움직인다.
 * @property mark 추가·삭제 표시. 문맥 줄은 `null` 이다.
 */
@Immutable
data class DiffCell(
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String,
    val changedRanges: List<IntRange>,
    val mark: DiffChangeMark?,
)

/**
 * `LazyColumn` 한 항목. [key] 는 평탄화된 목록 안의 행 인덱스로, 같은 diff 를 다시 그리는 동안
 * 바뀌지 않아 스크롤이 전체 재구성으로 번지지 않는다 (compose-ui 규칙 3).
 */
@Immutable
sealed interface DiffRow {

    val key: Int

    data class HunkHeader(override val key: Int, val hunkIndex: Int, val hunk: DiffHunk) : DiffRow

    data class Unified(override val key: Int, val cell: DiffCell) : DiffRow

    /** 짝이 없는 쪽은 `null` 이다 — 삭제만 있거나 추가만 있는 구간에서 생긴다. */
    data class Split(override val key: Int, val left: DiffCell?, val right: DiffCell?) : DiffRow
}

/**
 * hunk 목록을 [viewMode] 에 맞는 행 목록으로 편다.
 *
 * 줄이 하나도 없는 hunk 는 헤더도 내지 않는다 — "표시할 변경이 없다" 를 화면이 안내로 다뤄야지
 * 헤더만 덩그러니 남으면 빈 화면으로 오인된다.
 */
fun diffRowsOf(hunks: List<DiffHunk>, viewMode: DiffViewMode, tabWidth: Int): List<DiffRow> {
    require(tabWidth > 0) { "탭 폭은 1 이상이어야 합니다: $tabWidth" }

    val rows = mutableListOf<DiffRow>()
    hunks.forEachIndexed { hunkIndex, hunk ->
        if (hunk.lines.isEmpty()) return@forEachIndexed
        rows += DiffRow.HunkHeader(key = rows.size, hunkIndex = hunkIndex, hunk = hunk)
        when (viewMode) {
            DiffViewMode.UNIFIED -> hunk.lines.forEach { line ->
                rows += DiffRow.Unified(key = rows.size, cell = line.toCell(tabWidth))
            }
            DiffViewMode.SPLIT -> rows += hunk.lines.toSplitRows(tabWidth, firstKey = rows.size)
        }
    }
    return rows
}

/**
 * 분할 뷰 짝짓기 — 문맥 줄은 양쪽에 같이 두고, 변경 구간은 **삭제 n번째 ↔ 추가 n번째**로 잇는다.
 * 짝짓기 규칙을 word-level 강조(`WordDiffCalculator`)와 같게 맞춰야 좌우 강조가 서로 다른 줄을 가리키지 않는다.
 */
private fun List<DiffLine>.toSplitRows(tabWidth: Int, firstKey: Int): List<DiffRow.Split> {
    val rows = mutableListOf<DiffRow.Split>()
    var index = 0
    while (index < size) {
        val line = this[index]
        if (line.type == DiffLineType.CONTEXT) {
            val cell = line.toCell(tabWidth)
            rows += DiffRow.Split(key = firstKey + rows.size, left = cell, right = cell)
            index++
            continue
        }
        val deleted = mutableListOf<DiffCell>()
        while (index < size && this[index].type == DiffLineType.DELETED) {
            deleted += this[index].toCell(tabWidth)
            index++
        }
        val added = mutableListOf<DiffCell>()
        while (index < size && this[index].type == DiffLineType.ADDED) {
            added += this[index].toCell(tabWidth)
            index++
        }
        repeat(maxOf(deleted.size, added.size)) { offset ->
            rows += DiffRow.Split(
                key = firstKey + rows.size,
                left = deleted.getOrNull(offset),
                right = added.getOrNull(offset),
            )
        }
    }
    return rows
}

private fun DiffLine.toCell(tabWidth: Int): DiffCell {
    val expanded = expandTabs(content, changedRanges, tabWidth)
    return DiffCell(
        oldLineNumber = oldLineNumber,
        newLineNumber = newLineNumber,
        text = expanded.text,
        changedRanges = expanded.changedRanges,
        mark = when (type) {
            DiffLineType.CONTEXT -> null
            DiffLineType.ADDED -> DiffChangeMark.ADDITION
            DiffLineType.DELETED -> DiffChangeMark.DELETION
        },
    )
}

internal data class ExpandedLine(val text: String, val changedRanges: List<IntRange>)

/**
 * 탭 하나를 [tabWidth] 칸 공백으로 편다 (탭 스톱 정렬이 아니라 고정 폭이다 — 좌우 열의 같은 열
 * 위치가 서로 어긋나지 않는 쪽을 택했다). 본문이 늘어난 만큼 [ranges] 도 같은 자리로 옮긴다.
 *
 * 본문 밖을 가리키는 구간은 본문 길이 안으로 자른다 — 잘못된 구간 하나가 화면 전체를 크래시로 만들지 않는다.
 */
internal fun expandTabs(content: String, ranges: List<IntRange>, tabWidth: Int): ExpandedLine {
    val bounded = ranges.mapNotNull { it.boundedTo(content.length) }
    if (TAB !in content) return ExpandedLine(content, bounded)

    val builder = StringBuilder(content.length)
    val offsets = IntArray(content.length + 1)
    content.forEachIndexed { index, character ->
        offsets[index] = builder.length
        if (character == TAB) repeat(tabWidth) { builder.append(SPACE) } else builder.append(character)
    }
    offsets[content.length] = builder.length

    return ExpandedLine(
        text = builder.toString(),
        changedRanges = bounded.map { offsets[it.first]..offsets[it.last + 1] - 1 },
    )
}

/** 본문 길이 안으로 자른 구간. 겹치는 부분이 없으면 `null`. */
private fun IntRange.boundedTo(length: Int): IntRange? {
    val start = maxOf(first, 0)
    val end = minOf(last, length - 1)
    return if (start > end) null else start..end
}
