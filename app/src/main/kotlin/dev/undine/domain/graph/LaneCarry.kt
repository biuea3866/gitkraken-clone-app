package dev.undine.domain.graph

/**
 * 페이지 경계를 넘겨 이어 가는 **불변** 레인 상태. 활성 레인 표현은 노출하지 않는다 —
 * 호출부는 [GraphPage.carry] 를 받아 다음 [GraphLaneAssigner.assign] 에 그대로 넘기기만 한다.
 *
 * carry 가 기대한 커밋이 다음 입력에 없으면 그 레인은 닫힌다 (사용자가 스크롤 중 브랜치를 바꾸면
 * 정상적으로 발생한다).
 */
class LaneCarry internal constructor(
    internal val lanes: List<ActiveLane?>,
    internal val colorCursor: Int,
) {
    /** 다음 페이지로 이어지는 활성 레인 개수. 진단·테스트용 요약값이다. */
    val activeLaneCount: Int get() = lanes.count { it != null }
}
