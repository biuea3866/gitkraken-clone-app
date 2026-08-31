package dev.undine.domain.merge

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryBaseline

/**
 * 리베이스 결과. 시작·계속·건너뛰기가 모두 이 타입을 돌려준다 — 세 연산의 종료 조건이 같기 때문이다.
 *
 * 병합과 마찬가지로 **충돌은 예외가 아니라 [Conflicted]** 다.
 */
sealed interface RebaseResult {

    /**
     * 재배치가 끝나 HEAD 가 [head] 다.
     *
     * [previousHead] 와 [baseline] 은 **재배치와 같은 임계 구역에서** 캡처한 되돌리기 재료다
     * ([MergeResult.Succeeded] 와 같은 이유, UND-73). [previousHead] 는 시작 전 지점(`ORIG_HEAD`)이라,
     * 충돌을 해결하고 이어간 리베이스도 **리베이스를 시작하기 전** 커밋으로 되돌아간다.
     */
    data class Succeeded(
        val head: CommitId,
        val previousHead: CommitId?,
        val baseline: RepositoryBaseline,
    ) : RebaseResult

    /** 충돌한 파일 목록. 저장소는 리베이스 진행 중(RepositoryState.REBASING)으로 남는다. */
    data class Conflicted(val paths: List<String>) : RebaseResult

    /** 이미 대상 위에 있어 재배치할 커밋이 없다. */
    data object AlreadyUpToDate : RebaseResult
}
