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

        /**
         * 한쪽 이상이 Git LFS 포인터다.
         *
         * 포인터 원문(`version https://git-lfs.github.com/spec/v1` 로 시작하는 몇 줄)은 계산할 수는
         * 있지만 **보여 주면 안 되는** 내용이다 — 사용자는 그것을 파일 내용으로 읽고 "파일이
         * 깨졌다" 고 오해한다. [BINARY]·[TOO_LARGE] 와 같은 층의 "계산하지 않은 사유" 라
         * 별도 타입으로 가르지 않고 값 하나를 더한다.
         */
        LFS_POINTER,
    }
}
