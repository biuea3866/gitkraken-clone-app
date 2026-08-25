package dev.undine.domain

/**
 * 저장소 상태. 뒤 wave 가 필요로 하는 상태는 그 티켓이 추가한다 (bisect 는 UND-35 가 추가했다).
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

    /** cherry-pick 이 충돌해 진행 중이다. 해결해 이어가거나 중단해야 빠져나온다. */
    CHERRY_PICKING,

    /**
     * 이분 탐색(bisect)이 진행 중이다. HEAD 는 검사 대상 커밋에 detached 로 붙어 있지만
     * [DETACHED] 로 뭉개지 않는다 — 화면이 bisect 중임을 알아야 continue/reset 을 안내할 수 있다.
     */
    BISECTING,
    DETACHED,
}
