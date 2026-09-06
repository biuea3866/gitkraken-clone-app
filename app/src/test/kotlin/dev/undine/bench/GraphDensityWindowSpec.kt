package dev.undine.bench

import dev.undine.domain.Branch
import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** 배지가 깊숙이 놓인 위치. 첫 화면에는 들어오지 않아 구간을 옮겨야 보인다. */
private const val DEEP_BADGE_INDEX = 30

/** 픽스처 이력 길이. [DEEP_BADGE_INDEX] 에서 시작해도 화면이 가득 차야 한다. */
private const val FIXTURE_HISTORY_SIZE = 80

/**
 * 렌더 구간 고르기 규칙 (UND-89).
 *
 * **벤치 저장소 없이 도는 순수 검증**이다 — 어떤 구간을 밀도 검토 재료로 인정할지는 실제 저장소가
 * 있든 없든 같은 규칙이고, 그 규칙이 틀리면 렌더는 "레인이 둘뿐인 화면"을 남기고도 통과한다.
 * 실제 저장소로 렌더하는 쪽은 [GraphDensityRenderSpec] 이다.
 */
class GraphDensityWindowSpec : FunSpec({

    test("레인이 둘 이하인 구간은 배지가 있어도 밀도 검토 재료로 인정하지 않는다") {
        val linear = linearHistory(FIXTURE_HISTORY_SIZE)

        selectDenseWindow(linear, refIndexAt(0)).shouldBeNull()
    }

    test("ref 배지가 없는 구간은 레인이 여럿이어도 인정하지 않는다") {
        val braided = braidedHistory(FIXTURE_HISTORY_SIZE)

        selectDenseWindow(braided, CommitRefIndex.EMPTY).shouldBeNull()
    }

    test("레인이 셋 이상이고 배지가 보이는 구간을 고른다") {
        val braided = braidedHistory(FIXTURE_HISTORY_SIZE)

        val window = selectDenseWindow(braided, refIndexAt(0)).shouldNotBeNull()

        window shouldHaveSize DENSITY_WINDOW_SIZE
        window.first() shouldBe braided.first()
        visibleRowsOf(window).maxOf { it.laneSpan } shouldBeGreaterThanOrEqual DENSITY_MINIMUM_LANES
    }

    test("배지가 첫 화면 밖에 있으면 배지가 맨 위에 오는 구간으로 옮긴다") {
        val braided = braidedHistory(FIXTURE_HISTORY_SIZE)

        val window = selectDenseWindow(braided, refIndexAt(DEEP_BADGE_INDEX)).shouldNotBeNull()

        window.first() shouldBe braided[DEEP_BADGE_INDEX]
    }

    test("배지가 이력 끝에만 있으면 배지가 아랫행에 오는 구간으로 옮긴다") {
        val braided = braidedHistory(FIXTURE_HISTORY_SIZE)
        val lastIndex = FIXTURE_HISTORY_SIZE - 1

        val window = selectDenseWindow(braided, refIndexAt(lastIndex)).shouldNotBeNull()

        window.first() shouldBe braided[lastIndex - (DENSITY_VISIBLE_ROWS - 1)]
        visibleRowsOf(window).last().commit shouldBe braided[lastIndex]
    }

    test("화면을 다 채우지 못하는 꼬리 구간은 인정하지 않는다") {
        val tail = braidedHistory(DENSITY_VISIBLE_ROWS - 1)

        selectDenseWindow(tail, refIndexAt(0)).shouldBeNull()
    }
})

/** `refs/heads/main` 단독 이력처럼 레인이 하나로 이어지는 사슬. */
private fun linearHistory(size: Int): List<Commit> =
    (1..size).map { seed -> commit(seed, seed + 1, message = "linear $seed") }

/**
 * 레인 [DENSITY_MINIMUM_LANES] 개가 나란히 열린 채 이어지는 이력.
 *
 * 커밋 `n` 의 부모를 `n + 레인 수` 로 두면, 앞선 레인이 자기 부모를 만나기 전에 다음 커밋이
 * 새 레인을 열어 레인 수가 그만큼 유지된다.
 */
private fun braidedHistory(size: Int): List<Commit> =
    (1..size).map { seed -> commit(seed, seed + DENSITY_MINIMUM_LANES, message = "braided $seed") }

/** [index] 번째 커밋에만 브랜치 배지가 붙은 색인. */
private fun refIndexAt(index: Int): CommitRefIndex = CommitRefIndex.of(
    branches = listOf(
        Branch(
            name = RefName("dense"),
            target = commitId(index + 1),
            isCurrent = false,
            isRemote = false,
            upstream = null,
            ahead = 0,
            behind = 0,
        ),
    ),
    tags = emptyList(),
    currentBranch = null,
)
