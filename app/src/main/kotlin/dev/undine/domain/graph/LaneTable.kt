package dev.undine.domain.graph

import dev.undine.domain.CommitId

/** [LaneTable.place] 결과 — 커밋이 놓인 레인과 그 레인의 색 슬롯. */
internal data class LanePlacement(
    val lane: Int,
    val colorSlot: Int,
)

/**
 * 배치 중에만 사는 **가변** 활성 레인 테이블. `null` 칸은 닫힌 레인이며 이후 커밋이 재사용한다 —
 * 재사용하지 않으면 오래된 저장소에서 레인이 수백 개로 늘어난다.
 *
 * 불변 경계는 [LaneCarry] 다: [from] 으로 이어받고 [toCarry] 로 내보낸다.
 */
internal class LaneTable private constructor(
    private val lanes: MutableList<ActiveLane?>,
    private var colorCursor: Int,
) {

    /** [presentIds] 에 없는 커밋을 기다리는 레인을 닫는다. 예외를 던지지 않고 그 레인만 포기한다. */
    fun closeLanesNotExpecting(presentIds: Set<CommitId>) {
        for (index in lanes.indices) {
            val active = lanes[index] ?: continue
            if (active.expected !in presentIds) lanes[index] = null
        }
    }

    /**
     * [commitId] 를 기다리던 레인에 커밋을 놓고 그 기대를 비운다.
     * 기다리는 레인이 없으면 **가장 왼쪽 빈 레인**을 열고 새 색 슬롯을 배정한다 (브랜치 시작점).
     */
    fun place(commitId: CommitId): LanePlacement {
        val waiting = laneWaitingFor(commitId)
        if (waiting == LaneEdge.NO_LANE) {
            return LanePlacement(lane = freeLane(), colorSlot = nextColorSlot())
        }
        val colorSlot = requireNotNull(lanes[waiting]).colorSlot
        lanes[waiting] = null
        return LanePlacement(lane = waiting, colorSlot = colorSlot)
    }

    /** [exceptLane] 을 뺀, 이 행을 통과만 하는 선들. */
    fun activeSegments(exceptLane: Int): List<LaneSegment> =
        lanes.mapIndexedNotNull { index, active ->
            if (index == exceptLane || active == null) null else LaneSegment(index, active.colorSlot)
        }

    /**
     * 첫 부모를 등록한다. 기본은 [lane] 이 계속 기다리는 것이고, 같은 부모를 기다리는 레인이 이미
     * 있으면 **왼쪽 레인으로 합치고 오른쪽 레인을 닫는다** (브랜치 합류).
     *
     * @return 부모가 놓일 레인 인덱스.
     */
    fun continueIn(lane: Int, colorSlot: Int, parent: CommitId): Int {
        val waiting = laneWaitingFor(parent)
        if (waiting != LaneEdge.NO_LANE && waiting < lane) {
            return waiting
        }
        if (waiting != LaneEdge.NO_LANE) lanes[waiting] = null
        lanes[lane] = ActiveLane(expected = parent, colorSlot = colorSlot)
        return lane
    }

    /**
     * 2번째 이후 부모를 등록한다 (병합선). 그 부모를 이미 기다리는 레인이 있으면 그 레인으로 합치고,
     * 없으면 가장 왼쪽 빈 레인을 새로 연다.
     *
     * @return 부모가 놓일 레인 인덱스.
     */
    fun openFor(parent: CommitId): Int {
        val waiting = laneWaitingFor(parent)
        if (waiting != LaneEdge.NO_LANE) return waiting
        val lane = freeLane()
        lanes[lane] = ActiveLane(expected = parent, colorSlot = nextColorSlot())
        return lane
    }

    /** 다음 페이지로 넘길 불변 상태. 뒤쪽 빈 레인은 잘라내 테이블이 무한히 길어지지 않게 한다. */
    fun toCarry(): LaneCarry = LaneCarry(
        lanes = lanes.dropLastWhile { it == null },
        colorCursor = colorCursor,
    )

    private fun laneWaitingFor(commitId: CommitId): Int =
        lanes.indexOfFirst { it?.expected == commitId }

    private fun freeLane(): Int {
        val free = lanes.indexOfFirst { it == null }
        if (free != LaneEdge.NO_LANE) return free
        lanes.add(null)
        return lanes.lastIndex
    }

    /**
     * 레인을 열 때 배정할 색 슬롯. 커서를 순환시키며 **현재 열려 있는 레인이 쓰는 슬롯은 건너뛴다.**
     * 동시에 열린 레인이 [GraphLaneAssigner.COLOR_SLOT_COUNT] 개를 넘으면 회피가 불가능하므로
     * **색 중복을 허용**한다 — 선을 안 그리는 것보다 색이 겹치는 게 낫다.
     */
    private fun nextColorSlot(): Int {
        val usedSlots = lanes.mapNotNull { it?.colorSlot }.toSet()
        var candidate = takeColorSlot()
        var attempts = 1
        while (candidate in usedSlots && attempts < GraphLaneAssigner.COLOR_SLOT_COUNT) {
            candidate = takeColorSlot()
            attempts++
        }
        return candidate
    }

    private fun takeColorSlot(): Int {
        val slot = colorCursor
        colorCursor = (colorCursor + 1) % GraphLaneAssigner.COLOR_SLOT_COUNT
        return slot
    }

    companion object {
        fun from(carry: LaneCarry?): LaneTable = LaneTable(
            lanes = carry?.lanes?.toMutableList() ?: mutableListOf(),
            colorCursor = carry?.colorCursor ?: 0,
        )
    }
}
