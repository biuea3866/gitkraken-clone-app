package dev.undine.domain

/**
 * 브랜치 삭제 결과. 미병합 브랜치는 `force` 없이 삭제되지 않으므로,
 * 성공 여부와 미병합 여부를 두 개의 Boolean 으로 갖지 않고 닫힌 두 값으로 표현한다.
 */
enum class DeleteBranchResult {
    DELETED,
    REFUSED_UNMERGED,
}
