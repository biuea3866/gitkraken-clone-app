package dev.undine.presentation.diff

/** diff 배치 방식. 같은 [dev.undine.domain.DiffResult.Computed] 내용을 서로 다른 레이아웃으로 편다. */
enum class DiffViewMode {
    /** 삭제·추가를 한 열에 원래 순서대로 이어 붙인다. */
    UNIFIED,

    /** 원본과 변경본을 좌우 두 열로 나란히 놓는다. */
    SPLIT,
}
