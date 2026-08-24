package dev.undine.presentation.search

/**
 * 검색 진행 단계. 화면이 `when` 으로 빠짐없이 분기하도록 닫아 둔다 (kotlin-idioms 4).
 *
 * [Running] 과 [Completed] 를 나누는 것이 이 타입의 존재 이유다 — 결과 0건과 "아직 찾는 중" 을
 * 한 상태로 합치면 화면이 둘을 구분해 표시할 수 없다.
 */
sealed interface SearchPhase {

    /** 조건이 하나도 없어 검색을 시작하지 않은 상태 (wave 3 결정 §UND-20). */
    data object Idle : SearchPhase

    /** 이력을 훑는 중이다. 이 단계에서도 결과는 찾는 대로 화면에 추가된다. */
    data object Running : SearchPhase

    /** 전체 순회가 끝났다. 결과가 0건이면 "찾은 커밋 없음" 이 확정된 상태다. */
    data object Completed : SearchPhase

    /**
     * 순회가 실패로 끝났다.
     *
     * 실패를 0건으로 바꾸지 않는다 — 조회 실패를 빈 결과로 보여주면 사용자가 "그런 커밋이 없다" 고
     * 오해한다 (exception-handling 7). [cause] 는 원문을 그대로 보존한다.
     */
    data class Failed(val cause: Throwable) : SearchPhase
}
