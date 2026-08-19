package dev.undine.domain

/**
 * 저장소 상태. 이 티켓은 정상·병합중·리베이스중·detached 네 값으로 닫는다 —
 * cherry-pick·bisect 처럼 뒤 wave 가 필요로 하는 상태는 그 티켓이 추가한다.
 */
enum class RepositoryState {
    NORMAL,
    MERGING,
    REBASING,
    DETACHED,
}
