package dev.undine.domain.merge

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryBaseline

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
     *
     * [previousHead] 와 [baseline] 은 **병합과 같은 임계 구역에서** 캡처한 되돌리기 재료다 (UND-73).
     * 되돌리기를 기록하는 호출자가 병합 뒤 스스로 읽으면 그 읽기와 병합 사이에 앱 내부의 다른
     * 조작이 끼어들어, 되돌리기가 남의 변경 위에서 실행된다. 되돌릴 기록을 남기는 변이가 이것뿐이라
     * **여기에만** 담는다 — [Conflicted] 는 진행 중이고 [AlreadyUpToDate] 는 바꾼 것이 없다.
     *
     * [previousHead] 는 시작 전 지점(`ORIG_HEAD`)이다. 커밋이 하나도 없던 저장소는 되돌릴 지점이
     * 없어 null 이다.
     */
    data class Succeeded(
        val head: CommitId,
        val fastForward: Boolean,
        val previousHead: CommitId?,
        val baseline: RepositoryBaseline,
    ) : MergeResult

    /** 충돌한 파일 목록. 저장소는 병합 진행 중(RepositoryState.MERGING)으로 남는다. */
    data class Conflicted(val paths: List<String>) : MergeResult

    /** 대상이 이미 병합돼 있어 변경이 없다. */
    data object AlreadyUpToDate : MergeResult
}
