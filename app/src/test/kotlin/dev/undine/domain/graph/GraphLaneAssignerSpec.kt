package dev.undine.domain.graph

import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.Person
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

private val FIXED_TIME = Instant.parse("2026-01-01T00:00:00Z")
private val AUTHOR = Person(name = "Undine", email = "undine@example.com")

/** 커밋 해시는 seed 를 40자 hex 로 채워 만든다 — 테스트가 시간·난수에 의존하지 않게 고정값만 쓴다. */
private fun commitId(seed: Int): CommitId = CommitId.of(seed.toString(16).padStart(40, '0'))

private fun commit(seed: Int, vararg parents: Int): Commit = Commit(
    id = commitId(seed),
    parents = parents.map(::commitId),
    message = "commit $seed",
    author = AUTHOR,
    committer = AUTHOR,
    authoredAt = FIXED_TIME,
    committedAt = FIXED_TIME,
)

class GraphLaneAssignerSpec : FunSpec({

    test("선형 이력은 모든 커밋이 레인 0 에 배치되고 통과선이 없다") {
        val page = GraphLaneAssigner.assign(listOf(commit(1, 2), commit(2, 3), commit(3)))

        page.rows shouldHaveSize 3
        page.rows.map { it.lane } shouldContainExactly listOf(0, 0, 0)
        page.rows.forEach { it.passThrough.shouldBeEmpty() }
        page.rows.map { it.colorSlot }.distinct() shouldHaveSize 1
    }

    test("선형 이력의 부모 선은 같은 레인을 잇는 STRAIGHT 다") {
        val page = GraphLaneAssigner.assign(listOf(commit(1, 2), commit(2)))

        page.rows[0].parents shouldContainExactly listOf(LaneEdge(0, 0, EdgeKind.STRAIGHT))
    }

    test("브랜치가 갈라지면 새 커밋이 레인 1 에 배치되고 통과선이 유지된다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 4), commit(2, 3), commit(3, 4), commit(4)),
        )

        page.rows.map { it.lane } shouldContainExactly listOf(0, 1, 1, 0)
        page.rows[1].passThrough shouldContainExactly listOf(LaneSegment(0, page.rows[0].colorSlot))
        page.rows[2].passThrough shouldContainExactly listOf(LaneSegment(0, page.rows[0].colorSlot))
        page.rows[3].passThrough.shouldBeEmpty()
    }

    test("부모가 왼쪽 레인으로 합쳐지면 그 선은 BRANCH 다") {
        val page = GraphLaneAssigner.assign(listOf(commit(1, 3), commit(2, 3), commit(3)))

        page.rows[1].parents shouldContainExactly listOf(LaneEdge(1, 0, EdgeKind.BRANCH))
        page.rows[2].lane shouldBe 0
    }

    test("병합 커밋은 두 부모 레인을 잇는 병합선을 만든다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 2, 3), commit(2, 4), commit(3, 4), commit(4)),
        )

        page.rows[0].parents shouldContainExactly listOf(
            LaneEdge(0, 0, EdgeKind.STRAIGHT),
            LaneEdge(0, 1, EdgeKind.MERGE),
        )
        page.rows[1].lane shouldBe 0
        page.rows[2].lane shouldBe 1
    }

    test("부모가 3개인 옥토퍼스 병합도 레인이 끊기지 않는다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 2, 3, 4), commit(2, 5), commit(3, 5), commit(4, 5), commit(5)),
        )

        page.rows[0].parents shouldContainExactly listOf(
            LaneEdge(0, 0, EdgeKind.STRAIGHT),
            LaneEdge(0, 1, EdgeKind.MERGE),
            LaneEdge(0, 2, EdgeKind.MERGE),
        )
        page.rows.map { it.lane } shouldContainExactly listOf(0, 0, 1, 2, 0)
        page.rows[3].passThrough.map { it.lane } shouldContainExactly listOf(0)
    }

    test("parents 는 Commit.parents 와 1:1 로 대응해 순서와 개수가 유지된다") {
        val octopus = commit(1, 2, 9, 4)
        val page = GraphLaneAssigner.assign(listOf(octopus, commit(2), commit(4)))

        page.rows[0].parents shouldHaveSize octopus.parents.size
        page.rows[0].parents[1].toLane shouldBe LaneEdge.NO_LANE
        page.rows[0].parents[2].toLane shouldBeGreaterThan 0
    }

    test("닫힌 레인의 인덱스는 이후 커밋이 재사용한다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 3), commit(2, 3), commit(3, 4), commit(4, 6), commit(5, 6), commit(6)),
        )

        page.rows.map { it.lane } shouldContainExactly listOf(0, 1, 0, 0, 1, 0)
    }

    test("레인이 재사용돼도 색 슬롯이 이전 레인과 겹치지 않는다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 3), commit(2, 3), commit(3, 4), commit(4, 6), commit(5, 6), commit(6)),
        )

        val firstLaneOne = page.rows[1].colorSlot
        val reusedLaneOne = page.rows[4].colorSlot
        reusedLaneOne shouldNotBe firstLaneOne
    }

    test("새 레인은 활성 레인이 쓰는 색 슬롯을 건너뛴다") {
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, 2, 3, 4), commit(2, 5), commit(3, 5), commit(4, 5), commit(5)),
        )

        val activeSlots = listOf(page.rows[0], page.rows[2], page.rows[3]).map { it.colorSlot }
        activeSlots shouldContainExactly activeSlots.distinct()
    }

    test("레인이 12개를 넘으면 색 슬롯을 재사용한다") {
        val parents = (2..14).toList()
        val page = GraphLaneAssigner.assign(
            listOf(commit(1, *parents.toIntArray())) + parents.map { commit(it) },
        )

        val openedSlots = page.rows.map { it.colorSlot }
        openedSlots shouldHaveSize 14
        openedSlots.all { it in 0 until GraphLaneAssigner.COLOR_SLOT_COUNT } shouldBe true
        openedSlots.distinct().size shouldBe GraphLaneAssigner.COLOR_SLOT_COUNT
    }

    test("커밋 0건 입력은 빈 결과와 다음 페이지에 넘길 수 있는 carry 를 반환한다") {
        val page = GraphLaneAssigner.assign(emptyList())

        page.rows.shouldBeEmpty()
        page.carry.activeLaneCount shouldBe 0
        GraphLaneAssigner.assign(listOf(commit(1)), page.carry).rows shouldHaveSize 1
    }

    test("페이지 경계에서 이전 레인 상태를 이어받으면 통과선이 끊기지 않는다") {
        val firstPage = GraphLaneAssigner.assign(listOf(commit(1, 4), commit(2, 3)))
        firstPage.carry.activeLaneCount shouldBe 2

        val secondPage = GraphLaneAssigner.assign(listOf(commit(3, 4), commit(4)), firstPage.carry)

        secondPage.rows[0].lane shouldBe 1
        secondPage.rows[0].colorSlot shouldBe firstPage.rows[1].colorSlot
        secondPage.rows[0].passThrough shouldContainExactly listOf(
            LaneSegment(0, firstPage.rows[0].colorSlot),
        )
        secondPage.rows[0].parents shouldContainExactly listOf(LaneEdge(1, 0, EdgeKind.BRANCH))
        secondPage.rows[1].lane shouldBe 0
    }

    test("입력에 없는 부모는 예외 없이 선을 그리지 않고 항목만 남는다") {
        val page = GraphLaneAssigner.assign(listOf(commit(1, 9)))

        page.rows[0].parents shouldContainExactly listOf(LaneEdge(0, LaneEdge.NO_LANE, EdgeKind.STRAIGHT))
        page.carry.activeLaneCount shouldBe 1
    }

    test("carry 가 기대한 커밋이 없으면 그 레인만 닫고 계속한다") {
        val firstPage = GraphLaneAssigner.assign(listOf(commit(1, 8), commit(2, 3)))
        firstPage.carry.activeLaneCount shouldBe 2

        val secondPage = GraphLaneAssigner.assign(listOf(commit(3)), firstPage.carry)

        secondPage.rows[0].lane shouldBe 1
        secondPage.rows[0].passThrough.shouldBeEmpty()
        secondPage.carry.activeLaneCount shouldBe 0
    }

    test("중복 커밋 ID 는 IllegalArgumentException 이다") {
        val duplicated = listOf(commit(1, 2), commit(1, 3))

        shouldThrow<IllegalArgumentException> { GraphLaneAssigner.assign(duplicated) }
    }
})
