package dev.undine.domain.graph

import dev.undine.domain.CommitId

/**
 * 커밋 1건의 배치 결과. 좌표는 **레인 인덱스(정수)** 만 쓴다 — 픽셀 계산은 렌더러(UND-14) 몫이다.
 *
 * @property commit 이 행의 커밋.
 * @property lane 커밋이 놓인 레인 인덱스.
 * @property colorSlot 레인이 **열릴 때** 배정된 색 슬롯. 레인 인덱스가 아니라 이 값으로 색을 고른다 —
 *   닫힌 레인 인덱스는 재사용되므로 인덱스를 그대로 쓰면 색이 튄다.
 *   슬롯은 [GraphLaneAssigner.COLOR_SLOT_COUNT] 개를 순환하며, 동시에 열린 레인이 그보다 많으면
 *   **색 중복을 허용**한다 (인접 회피는 최선 노력이다).
 * @property passThrough 이 행을 통과만 하는 선들. 커밋 자신의 레인은 포함하지 않는다.
 * @property parents 부모로 이어지는 선들. `Commit.parents` 와 **순서·개수가 1:1 로 대응**한다.
 */
data class GraphRow(
    val commit: CommitId,
    val lane: Int,
    val colorSlot: Int,
    val passThrough: List<LaneSegment>,
    val parents: List<LaneEdge>,
)
