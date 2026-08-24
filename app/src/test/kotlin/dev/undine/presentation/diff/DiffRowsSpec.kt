package dev.undine.presentation.diff

import dev.undine.presentation.design.DiffChangeMark
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.FunSpec

private const val TAB_WIDTH = 4

/** 렌더링 없이 검증 가능한 축 — hunk → 행 평탄화, 분할 짝짓기, 탭 확장과 강조 구간 이동. */
class DiffRowsSpec : FunSpec({

    test("통합 뷰는 hunk 헤더 다음에 줄을 원래 순서대로 편다") {
        val rows = diffRowsOf(listOf(singleLineEditHunk()), DiffViewMode.UNIFIED, TAB_WIDTH)

        rows.size shouldBe 5
        rows.first().shouldBeInstanceOf<DiffRow.HunkHeader>().hunkIndex shouldBe 0
        rows.drop(1).map { it.shouldBeInstanceOf<DiffRow.Unified>().cell.text } shouldContainExactly listOf(
            "fun main() {",
            "    val value = 1",
            "    val value = 2",
            "}",
        )
    }

    test("한 줄 수정은 삭제·추가 각각에 두 번호 열과 기호가 함께 붙는다") {
        val rows = diffRowsOf(listOf(singleLineEditHunk()), DiffViewMode.UNIFIED, TAB_WIDTH)
        val cells = rows.filterIsInstance<DiffRow.Unified>().map { it.cell }

        val deleted = cells[1]
        deleted.mark shouldBe DiffChangeMark.DELETION
        deleted.oldLineNumber shouldBe 2
        deleted.newLineNumber.shouldBeNull()

        val added = cells[2]
        added.mark shouldBe DiffChangeMark.ADDITION
        added.oldLineNumber.shouldBeNull()
        added.newLineNumber shouldBe 2

        val context = cells[0]
        context.mark.shouldBeNull()
        context.oldLineNumber shouldBe 1
        context.newLineNumber shouldBe 1
    }

    test("강조 구간은 gateway 가 준 changedRanges 를 그대로 옮긴다") {
        val rows = diffRowsOf(listOf(singleLineEditHunk()), DiffViewMode.UNIFIED, TAB_WIDTH)
        val cells = rows.filterIsInstance<DiffRow.Unified>().map { it.cell }

        cells[0].changedRanges.shouldBeEmpty()
        cells[1].changedRanges shouldContainExactly listOf(15..15)
        cells[2].changedRanges shouldContainExactly listOf(15..15)
    }

    test("분할 뷰는 문맥 줄을 양쪽에 같이 두고 변경 쌍을 좌우로 짝짓는다") {
        val rows = diffRowsOf(listOf(singleLineEditHunk()), DiffViewMode.SPLIT, TAB_WIDTH)
        val splits = rows.filterIsInstance<DiffRow.Split>()

        splits.size shouldBe 3
        splits[0].left.shouldNotBeNull().text shouldBe "fun main() {"
        splits[0].right.shouldNotBeNull().text shouldBe "fun main() {"

        splits[1].left.shouldNotBeNull().mark shouldBe DiffChangeMark.DELETION
        splits[1].right.shouldNotBeNull().mark shouldBe DiffChangeMark.ADDITION
        splits[1].left.shouldNotBeNull().oldLineNumber shouldBe 2
        splits[1].right.shouldNotBeNull().newLineNumber shouldBe 2

        splits[2].left.shouldNotBeNull().text shouldBe "}"
    }

    test("분할 뷰에서 짝이 모자란 쪽은 빈 칸으로 남는다") {
        val rows = diffRowsOf(listOf(unevenHunk()), DiffViewMode.SPLIT, TAB_WIDTH)
        val splits = rows.filterIsInstance<DiffRow.Split>()

        splits.size shouldBe 3
        splits[1].left.shouldNotBeNull().text shouldBe "first"
        splits[1].right.shouldNotBeNull().text shouldBe "merged"
        splits[2].left.shouldNotBeNull().text shouldBe "second"
        splits[2].right.shouldBeNull()
    }

    test("두 뷰 모드가 같은 내용을 담고 통합 쪽이 줄 순서를 보존한다") {
        val hunks = listOf(singleLineEditHunk())
        val unified = diffRowsOf(hunks, DiffViewMode.UNIFIED, TAB_WIDTH)
            .filterIsInstance<DiffRow.Unified>()
            .map { it.cell.text }
        val split = diffRowsOf(hunks, DiffViewMode.SPLIT, TAB_WIDTH)
            .filterIsInstance<DiffRow.Split>()
            .flatMap { listOfNotNull(it.left, it.right) }
            .map { it.text }

        split.toSet() shouldBe unified.toSet()
    }

    test("행 key 는 목록 안에서 겹치지 않고 순서를 따른다") {
        val rows = diffRowsOf(listOf(singleLineEditHunk(), unevenHunk()), DiffViewMode.UNIFIED, TAB_WIDTH)

        rows.map { it.key } shouldContainExactly rows.indices.toList()
    }

    test("hunk 가 여러 개면 헤더가 각 hunk 마다 붙고 원본 hunk 를 그대로 들고 있다") {
        val hunks = listOf(singleLineEditHunk(), unevenHunk())
        val headers = diffRowsOf(hunks, DiffViewMode.UNIFIED, TAB_WIDTH)
            .filterIsInstance<DiffRow.HunkHeader>()

        headers.map { it.hunkIndex } shouldContainExactly listOf(0, 1)
        headers.map { it.hunk } shouldContainExactly hunks
    }

    test("탭은 설정 폭만큼 공백으로 펴지고 강조 구간이 같은 토큰을 계속 가리킨다") {
        val hunk = singleLineEditHunk().copy(
            lines = listOf(deletedLine("\tvalue", oldLineNumber = 1, changedRanges = listOf(1..5))),
        )

        val cell = diffRowsOf(listOf(hunk), DiffViewMode.UNIFIED, tabWidth = 4)
            .filterIsInstance<DiffRow.Unified>()
            .single()
            .cell

        cell.text shouldBe "    value"
        cell.changedRanges shouldContainExactly listOf(4..8)
        withClue("강조 구간이 펼친 본문에서도 같은 글자를 덮어야 한다") {
            cell.text.substring(4, 9) shouldBe "value"
        }
    }

    test("탭 폭을 바꾸면 확장 폭과 강조 구간이 함께 따라간다") {
        val hunk = singleLineEditHunk().copy(
            lines = listOf(deletedLine("\tvalue", oldLineNumber = 1, changedRanges = listOf(1..5))),
        )

        val cell = diffRowsOf(listOf(hunk), DiffViewMode.UNIFIED, tabWidth = 2)
            .filterIsInstance<DiffRow.Unified>()
            .single()
            .cell

        cell.text shouldBe "  value"
        cell.changedRanges shouldContainExactly listOf(2..6)
    }

    test("본문 밖을 가리키는 강조 구간은 본문 길이 안으로 잘린다") {
        val hunk = singleLineEditHunk().copy(
            lines = listOf(deletedLine("ab", oldLineNumber = 1, changedRanges = listOf(0..9))),
        )

        val cell = diffRowsOf(listOf(hunk), DiffViewMode.UNIFIED, TAB_WIDTH)
            .filterIsInstance<DiffRow.Unified>()
            .single()
            .cell

        cell.changedRanges shouldContainExactly listOf(0..1)
    }

    test("hunk 가 없거나 hunk 안에 줄이 없으면 행이 하나도 나오지 않는다") {
        diffRowsOf(emptyList(), DiffViewMode.UNIFIED, TAB_WIDTH).shouldBeEmpty()
        val emptyHunk = singleLineEditHunk().copy(lines = emptyList())
        diffRowsOf(listOf(emptyHunk), DiffViewMode.UNIFIED, TAB_WIDTH).shouldBeEmpty()
        diffRowsOf(listOf(emptyHunk), DiffViewMode.SPLIT, TAB_WIDTH).shouldBeEmpty()
    }
})
