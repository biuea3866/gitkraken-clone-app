package dev.undine.domain.graph

import dev.undine.domain.CommitId

/**
 * 활성 레인 한 칸 — "다음에 [expected] 커밋을 기다린다" 는 상태.
 * [LaneCarry] 의 내부 표현이므로 패키지 밖으로 노출하지 않는다.
 */
internal data class ActiveLane(
    val expected: CommitId,
    val colorSlot: Int,
)
