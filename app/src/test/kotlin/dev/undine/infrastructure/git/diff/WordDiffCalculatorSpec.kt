package dev.undine.infrastructure.git.diff

import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

private fun deletedLine(content: String): DiffLine =
    DiffLine(DiffLineType.DELETED, content, oldLineNumber = 1, newLineNumber = null, changedRanges = emptyList())

private fun addedLine(content: String): DiffLine =
    DiffLine(DiffLineType.ADDED, content, oldLineNumber = null, newLineNumber = 1, changedRanges = emptyList())

private fun contextLine(content: String): DiffLine =
    DiffLine(DiffLineType.CONTEXT, content, oldLineNumber = 1, newLineNumber = 1, changedRanges = emptyList())

class WordDiffCalculatorSpec : FunSpec({

    test("바뀐 토큰 구간만 0-based 문자 범위로 돌려준다") {
        val ranges = WordDiffCalculator.rangesOf("val a = 1", "val a = 2")

        ranges.deleted shouldContainExactly listOf(8..8)
        ranges.added shouldContainExactly listOf(8..8)
    }

    test("공백 경계로 토큰을 자르므로 단어 하나만 강조된다") {
        val ranges = WordDiffCalculator.rangesOf("foo bar baz", "foo qux baz")

        ranges.deleted shouldContainExactly listOf(4..6)
        ranges.added shouldContainExactly listOf(4..6)
    }

    test("영숫자와 기호 전환에서도 토큰을 자른다") {
        val ranges = WordDiffCalculator.rangesOf("a+b", "a-b")

        ranges.deleted shouldContainExactly listOf(1..1)
        ranges.added shouldContainExactly listOf(1..1)
    }

    test("같은 내용이면 강조 구간이 없다") {
        val ranges = WordDiffCalculator.rangesOf("same line", "same line")

        ranges.deleted.shouldBeEmpty()
        ranges.added.shouldBeEmpty()
    }

    test("추가만 있는 줄 쌍은 추가 구간에만 범위가 생긴다") {
        val ranges = WordDiffCalculator.rangesOf("count", "count + 1")

        ranges.deleted.shouldBeEmpty()
        ranges.added shouldContainExactly listOf(5..8)
    }

    test("hunk 안에서 삭제 n번째와 추가 n번째를 짝지어 계산한다") {
        val lines = listOf(
            deletedLine("first 1"),
            deletedLine("second 1"),
            addedLine("first 2"),
            addedLine("second 2"),
        )

        val marked = WordDiffCalculator.withChangedRanges(lines)

        marked[0].changedRanges shouldContainExactly listOf(6..6)
        marked[1].changedRanges shouldContainExactly listOf(7..7)
        marked[2].changedRanges shouldContainExactly listOf(6..6)
        marked[3].changedRanges shouldContainExactly listOf(7..7)
    }

    test("짝이 없는 변경 줄과 context 줄은 강조 구간을 갖지 않는다") {
        val lines = listOf(
            contextLine("unchanged"),
            deletedLine("val a = 1"),
            deletedLine("val b = 2"),
            addedLine("val a = 9"),
        )

        val marked = WordDiffCalculator.withChangedRanges(lines)

        marked[0].changedRanges.shouldBeEmpty()
        marked[1].changedRanges shouldContainExactly listOf(8..8)
        marked[2].changedRanges.shouldBeEmpty()
        marked[3].changedRanges shouldContainExactly listOf(8..8)
    }
})
