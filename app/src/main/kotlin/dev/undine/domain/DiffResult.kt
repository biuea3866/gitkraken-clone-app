package dev.undine.domain

/**
 * diff 계산 결과. 계산하지 않은 경우 빈 hunk 목록으로 돌려주지 않고 사유를 함께 올린다 —
 * "변경이 없다" 와 "계산하지 않았다" 를 UI 가 구분해야 한다.
 */
sealed interface DiffResult {

    data class Computed(val hunks: List<DiffHunk>) : DiffResult

    data class NotComputed(val reason: Reason) : DiffResult

    enum class Reason {
        BINARY,
        TOO_LARGE,
    }
}
