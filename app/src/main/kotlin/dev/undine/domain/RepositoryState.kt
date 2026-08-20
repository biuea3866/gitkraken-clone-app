package dev.undine.domain

/**
 * 저장소 상태. cherry-pick·bisect 처럼 뒤 wave 가 필요로 하는 상태는 그 티켓이 추가한다.
 *
 * 전부 HEAD 조건이다 — [EMPTY] 는 HEAD 가 아직 없는 ref 를 가리키는 상태(unborn)이고,
 * [DETACHED] 는 ref 가 아니라 커밋을 직접 가리키는 상태다.
 */
enum class RepositoryState {
    /** 정상. HEAD 가 존재하는 브랜치를 가리킨다. */
    NORMAL,

    /**
     * 커밋이 하나도 없다 (unborn HEAD). 그래프·diff·이력이 전부 비어 있으므로
     * 화면은 이 상태를 정상과 구분해 안내해야 한다.
     */
    EMPTY,
    MERGING,
    REBASING,

    /** revert 가 충돌해 진행 중이다. continue 또는 abort 로만 빠져나온다. */
    REVERTING,
    DETACHED,
}
