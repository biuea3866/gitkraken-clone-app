package dev.undine.infrastructure.git.staging

import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffLine
import dev.undine.domain.DiffLineType
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun context(content: String) =
    DiffLine(DiffLineType.CONTEXT, content, null, null, emptyList())

private fun added(content: String) =
    DiffLine(DiffLineType.ADDED, content, null, null, emptyList())

private fun deleted(content: String) =
    DiffLine(DiffLineType.DELETED, content, null, null, emptyList())

private const val FIVE_LINES = "line1\nline2\nline3\nline4\nline5\n"

/** 첫 줄을 고치는 hunk — `@@ -1,2 +1,2 @@`. */
private val FIRST_LINE_HUNK = DiffHunk(
    oldStart = 1,
    oldLineCount = 2,
    newStart = 1,
    newLineCount = 2,
    lines = listOf(deleted("line1"), added("line1x"), context("line2")),
)

/** 마지막 줄을 고치는 hunk — `@@ -4,2 +4,2 @@`. */
private val LAST_LINE_HUNK = DiffHunk(
    oldStart = 4,
    oldLineCount = 2,
    newStart = 4,
    newLineCount = 2,
    lines = listOf(context("line4"), deleted("line5"), added("line5x")),
)

class HunkPatchApplierSpec : FunSpec({

    test("선택한 hunk 만 적용하고 나머지 원문은 그대로 남긴다") {
        HunkPatchApplier.apply(FIVE_LINES, listOf(FIRST_LINE_HUNK)) shouldBe
            "line1x\nline2\nline3\nline4\nline5\n"
    }

    test("여러 hunk 를 함께 적용하면 순서와 무관하게 모두 반영된다") {
        HunkPatchApplier.apply(FIVE_LINES, listOf(LAST_LINE_HUNK, FIRST_LINE_HUNK)) shouldBe
            "line1x\nline2\nline3\nline4\nline5x\n"
    }

    test("hunk 목록이 비면 원문을 그대로 돌려준다") {
        HunkPatchApplier.apply(FIVE_LINES, emptyList()) shouldBe FIVE_LINES
    }

    test("빈 원문에 추가만 하는 hunk 는 oldStart 0 을 파일 처음으로 해석한다") {
        val hunk = DiffHunk(
            oldStart = 0,
            oldLineCount = 0,
            newStart = 1,
            newLineCount = 2,
            lines = listOf(added("new1"), added("new2")),
        )

        HunkPatchApplier.apply("", listOf(hunk)) shouldBe "new1\nnew2\n"
    }

    test("oldLineCount 가 0 인 삽입 hunk 는 oldStart 다음 줄에 끼워 넣는다") {
        val hunk = DiffHunk(
            oldStart = 3,
            oldLineCount = 0,
            newStart = 4,
            newLineCount = 1,
            lines = listOf(added("inserted")),
        )

        HunkPatchApplier.apply(FIVE_LINES, listOf(hunk)) shouldBe
            "line1\nline2\nline3\ninserted\nline4\nline5\n"
    }

    test("원문에 끝 개행이 없으면 결과에도 넣지 않는다") {
        HunkPatchApplier.apply("line1\nline2", listOf(FIRST_LINE_HUNK)) shouldBe "line1x\nline2"
    }

    test("context 줄이 원문과 다르면 StateViolation 으로 거부한다") {
        val exception = shouldThrow<UndineException.StateViolation> {
            HunkPatchApplier.apply("other1\nother2\n", listOf(FIRST_LINE_HUNK))
        }

        exception.detail shouldBe "스테이징할 변경이 현재 인덱스 내용과 맞지 않습니다"
    }

    test("원문 끝을 넘어가는 hunk 는 StateViolation 으로 거부한다") {
        val hunk = DiffHunk(
            oldStart = 9,
            oldLineCount = 1,
            newStart = 9,
            newLineCount = 1,
            lines = listOf(deleted("line9")),
        )

        shouldThrow<UndineException.StateViolation> { HunkPatchApplier.apply(FIVE_LINES, listOf(hunk)) }
    }

    test("서로 겹치는 hunk 는 StateViolation 으로 거부한다") {
        shouldThrow<UndineException.StateViolation> {
            HunkPatchApplier.apply(FIVE_LINES, listOf(FIRST_LINE_HUNK, FIRST_LINE_HUNK))
        }
    }
})
