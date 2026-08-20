package dev.undine.domain.graph

/**
 * 한 페이지 배치 결과.
 *
 * [carry] 를 다음 페이지의 [GraphLaneAssigner.assign] 에 그대로 넘기면 페이지 경계에서
 * 레인과 통과선이 끊기지 않는다.
 */
data class GraphPage(
    val rows: List<GraphRow>,
    val carry: LaneCarry,
)
