package dev.undine.domain.graph

/** 그 행을 **통과만** 하는 선 한 줄. [lane] 은 레인 인덱스, [colorSlot] 은 그 레인의 색 슬롯이다. */
data class LaneSegment(
    val lane: Int,
    val colorSlot: Int,
)
