package dev.undine.domain.graph

import dev.undine.domain.Commit
import dev.undine.domain.CommitId

/**
 * 커밋 목록을 레인에 배치하는 **순수 계산**. 저장소를 열지 않으므로 Gateway 가 없고,
 * 같은 입력에 항상 같은 결과를 낸다.
 *
 * 배치 규칙 — 최신 커밋부터 훑으며 활성 레인 테이블([LaneTable])을 유지한다:
 * 1. 현재 커밋을 기다리던 레인이 있으면 그 레인에, 없으면 가장 왼쪽 빈 레인에 놓는다.
 * 2. 첫 부모는 현재 레인이 계속 기다리고, 2번째 이후 부모는 새 레인이 기다린다 (병합선).
 * 3. 같은 부모를 기다리는 레인이 둘이면 왼쪽으로 합치고 오른쪽을 닫는다. 닫힌 인덱스는 재사용된다.
 *
 * 입력 이상은 예외로 막지 않는다 — 부모가 이 페이지에 없으면 선만 생략하고([LaneEdge.NO_LANE]),
 * carry 가 기대한 커밋이 없으면 그 레인만 닫는다. 페이징 경계에서 정상적으로 발생하는 상황이다.
 * 중복 커밋 ID 만 호출부 버그이므로 `IllegalArgumentException` 으로 막는다.
 */
object GraphLaneAssigner {

    /** 순환하는 색 슬롯 개수. 레인 인덱스가 아니라 이 슬롯으로 색을 고른다. */
    const val COLOR_SLOT_COUNT = 12

    private const val FIRST_PARENT = 0

    /**
     * [commits] 를 최신순으로 배치한다. [carry] 에 이전 페이지의 [GraphPage.carry] 를 넘기면
     * 페이지 경계에서 레인과 통과선이 끊기지 않는다.
     *
     * @throws IllegalArgumentException 같은 커밋 ID 가 두 번 들어온 경우.
     */
    fun assign(commits: List<Commit>, carry: LaneCarry? = null): GraphPage {
        val presentIds = commits.mapTo(mutableSetOf()) { it.id }
        require(presentIds.size == commits.size) { "중복 커밋 ID 가 입력에 있습니다" }

        val table = LaneTable.from(carry)
        table.closeLanesNotExpecting(presentIds)
        val rows = commits.map { assignRow(it, table, presentIds) }
        return GraphPage(rows = rows, carry = table.toCarry())
    }

    private fun assignRow(commit: Commit, table: LaneTable, presentIds: Set<CommitId>): GraphRow {
        val placement = table.place(commit.id)
        val passThrough = table.activeSegments(placement.lane)
        val parents = commit.parents.mapIndexed { index, parent ->
            parentEdge(parent, index, placement, table, presentIds)
        }
        return GraphRow(
            commit = commit.id,
            lane = placement.lane,
            colorSlot = placement.colorSlot,
            passThrough = passThrough,
            parents = parents,
        )
    }

    /**
     * 부모 하나에 대응하는 선. 레인 기대는 부모가 이 페이지에 없어도 등록한다 — 그래야 carry 가
     * 다음 페이지에서 그 선을 이어 갈 수 있다. 다만 이 페이지에서는 그릴 수 없으므로
     * `toLane` 은 [LaneEdge.NO_LANE] 으로 둔다.
     */
    private fun parentEdge(
        parent: CommitId,
        index: Int,
        placement: LanePlacement,
        table: LaneTable,
        presentIds: Set<CommitId>,
    ): LaneEdge {
        val targetLane = if (index == FIRST_PARENT) {
            table.continueIn(lane = placement.lane, colorSlot = placement.colorSlot, parent = parent)
        } else {
            table.openFor(parent)
        }
        val kind = when {
            index != FIRST_PARENT -> EdgeKind.MERGE
            targetLane == placement.lane -> EdgeKind.STRAIGHT
            else -> EdgeKind.BRANCH
        }
        val drawable = parent in presentIds
        return LaneEdge(
            fromLane = placement.lane,
            toLane = if (drawable) targetLane else LaneEdge.NO_LANE,
            kind = kind,
        )
    }
}
