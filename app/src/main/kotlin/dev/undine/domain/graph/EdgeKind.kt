package dev.undine.domain.graph

/**
 * 커밋과 부모를 잇는 선의 종류. 렌더러(UND-14)가 선 모양을 고르는 데만 쓴다.
 *
 * - [STRAIGHT] — 첫 부모가 커밋과 **같은 레인**을 계속 쓴다 (직선).
 * - [BRANCH] — 첫 부모가 **다른 레인**에 있다. 같은 부모를 기다리던 왼쪽 레인으로 합쳐지는 분기점이다.
 * - [MERGE] — 부모가 **2번째 이후**다 (병합선).
 */
enum class EdgeKind {
    STRAIGHT,
    BRANCH,
    MERGE,
}
