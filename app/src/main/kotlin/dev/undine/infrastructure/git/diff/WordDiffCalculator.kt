package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator

/** 한 쌍의 변경 줄에서 얻은 강조 구간. 인덱스는 각 줄 `content` 의 0-based 문자 위치다. */
internal data class WordRanges(val deleted: List<IntRange>, val added: List<IntRange>)

private enum class TokenClass { WHITESPACE, ALPHANUMERIC, SYMBOL }

/**
 * 라인 diff 위에서 계산하는 word-level 강조.
 *
 * 짝짓기는 **hunk 안에서 삭제 n번째 ↔ 추가 n번째** 단순 대응이다 (wave 2 결정).
 * 정교한 매칭보다 예측 가능한 쪽을 택했고, 짝이 없는 줄은 강조 구간을 갖지 않는다 —
 * 그 줄은 이미 줄 단위로 추가·삭제 표시가 되기 때문이다.
 */
internal object WordDiffCalculator {

    /** hunk 의 줄 목록에 강조 구간을 채워 돌려준다. context 줄은 빈 목록을 유지한다. */
    fun withChangedRanges(lines: List<DiffLine>): List<DiffLine> {
        val deletedPositions = lines.indices.filter { index -> lines[index].type == DiffLineType.DELETED }
        val addedPositions = lines.indices.filter { index -> lines[index].type == DiffLineType.ADDED }
        val ranges = HashMap<Int, List<IntRange>>()
        for (pair in 0 until minOf(deletedPositions.size, addedPositions.size)) {
            val deletedAt = deletedPositions[pair]
            val addedAt = addedPositions[pair]
            val computed = rangesOf(lines[deletedAt].content, lines[addedAt].content)
            ranges[deletedAt] = computed.deleted
            ranges[addedAt] = computed.added
        }
        return lines.mapIndexed { index, line ->
            ranges[index]?.let { line.copy(changedRanges = it) } ?: line
        }
    }

    /** 두 줄을 토큰 단위로 비교해 바뀐 문자 구간을 낸다. */
    fun rangesOf(deleted: String, added: String): WordRanges {
        val deletedTokens = tokenize(deleted)
        val addedTokens = tokenize(added)
        val edits = HistogramDiff().diff(
            TokenComparator,
            TokenSequence(deleted, deletedTokens),
            TokenSequence(added, addedTokens),
        )
        return WordRanges(
            deleted = edits.mapNotNull { edit -> spanOf(deletedTokens, edit.beginA, edit.endA) },
            added = edits.mapNotNull { edit -> spanOf(addedTokens, edit.beginB, edit.endB) },
        )
    }

    private fun spanOf(tokens: List<IntRange>, from: Int, to: Int): IntRange? =
        if (to <= from) null else tokens[from].first..tokens[to - 1].last

    /** 공백 경계와 영숫자/기호 전환에서 자른다. 토큰은 줄 전체를 빈틈없이 덮는다. */
    private fun tokenize(line: String): List<IntRange> {
        if (line.isEmpty()) return emptyList()
        val tokens = mutableListOf<IntRange>()
        var start = 0
        var currentClass = classOf(line[0])
        for (index in 1 until line.length) {
            val nextClass = classOf(line[index])
            if (nextClass != currentClass) {
                tokens += (start until index)
                start = index
                currentClass = nextClass
            }
        }
        tokens += (start until line.length)
        return tokens
    }

    private fun classOf(character: Char): TokenClass = when {
        character.isWhitespace() -> TokenClass.WHITESPACE
        character.isLetterOrDigit() -> TokenClass.ALPHANUMERIC
        else -> TokenClass.SYMBOL
    }
}

private class TokenSequence(private val line: String, private val tokens: List<IntRange>) : Sequence() {

    override fun size(): Int = tokens.size

    fun textOf(index: Int): String = line.substring(tokens[index].first, tokens[index].last + 1)
}

private object TokenComparator : SequenceComparator<TokenSequence>() {

    override fun equals(a: TokenSequence, ai: Int, b: TokenSequence, bi: Int): Boolean =
        a.textOf(ai) == b.textOf(bi)

    override fun hash(seq: TokenSequence, ptr: Int): Int = seq.textOf(ptr).hashCode()
}
