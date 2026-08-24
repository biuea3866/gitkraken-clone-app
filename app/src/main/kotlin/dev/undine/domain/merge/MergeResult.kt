package dev.undine.domain.merge

import dev.undine.domain.CommitId

/**
 * 병합 결과.
 *
 * **충돌은 실패가 아니다** — 사용자가 이어서 해결해야 하는 상태이므로 예외가 아니라 [Conflicted] 로
 * 돌려준다. 예외로 던지면 호출부가 정상 흐름과 오류를 구분하지 못한다.
 */
sealed interface MergeResult {

    /**
     * 병합이 끝나 HEAD 가 [head] 다.
     *
     * [fastForward] 가 true 면 새 병합 커밋 없이 HEAD 만 대상 커밋으로 옮겼다는 뜻이다 —
     * 화면이 "병합 커밋을 만들었다" 와 "빨리 감기 했다" 를 다르게 안내해야 하므로 결과에 담는다.
     */
    data class Succeeded(val head: CommitId, val fastForward: Boolean) : MergeResult

    /** 충돌한 파일 목록. 저장소는 병합 진행 중(RepositoryState.MERGING)으로 남는다. */
    data class Conflicted(val paths: List<String>) : MergeResult

    /** 대상이 이미 병합돼 있어 변경이 없다. */
    data object AlreadyUpToDate : MergeResult
}
