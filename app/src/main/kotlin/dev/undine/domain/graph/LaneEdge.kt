package dev.undine.domain.graph

/**
 * 한 행에서 커밋([fromLane])과 부모([toLane])를 잇는 선. **행 기준 상대 표현**이며
 * 픽셀·방향 계산은 렌더러(UND-14) 몫이다.
 *
 * 부모가 이 페이지 입력에 없으면 선을 그릴 수 없으므로 [toLane] 이 [NO_LANE] 이다.
 * 그래도 항목 자체는 남는다 — `GraphRow.parents` 는 `Commit.parents` 와 1:1 로 대응한다.
 */
data class LaneEdge(
    val fromLane: Int,
    val toLane: Int,
    val kind: EdgeKind,
) {
    companion object {
        /** 부모가 이 페이지에 없어 이을 레인이 없다는 표시. */
        const val NO_LANE = -1
    }
}
