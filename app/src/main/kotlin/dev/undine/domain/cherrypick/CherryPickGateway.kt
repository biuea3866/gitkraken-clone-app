package dev.undine.domain.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException

/** 커밋 하나를 적용한 결과. 여러 커밋의 결과는 [CherryPickService] 가 모아 [CherryPickResult] 로 만든다. */
sealed interface CherryPickStep {

    /**
     * 새 커밋이 만들어졌다.
     *
     * [previousHead] 와 [baseline] 은 **적용과 같은 임계 구역에서** 캡처한 되돌리기 재료다 (UND-73).
     * 여러 커밋을 적용하면 단계마다 다른 임계 구역이라, [CherryPickService] 가 첫 단계의
     * [previousHead] 와 마지막 단계의 [baseline] 을 묶어 하나의 되돌리기로 만든다.
     */
    data class Created(
        val commit: CommitId,
        val previousHead: CommitId?,
        val baseline: RepositoryBaseline,
    ) : CherryPickStep

    /** 적용할 변경이 없어 커밋을 만들지 않았다. */
    data object Empty : CherryPickStep

    /** 충돌로 멈췄다. 저장소는 진행 중으로 남는다. */
    data class Conflicted(val paths: List<String>) : CherryPickStep
}

/**
 * cherry-pick 의 **실행**을 맡는 외부 Git 접근 계약. 구현은 `CherryPickGatewayImpl` 이다.
 *
 * 규칙(시작 전 워킹트리 검사·적용 순서·확인 대조)은 [CherryPickService] 가 갖고, 여기에는 "무엇을
 * 실행하는가" 만 둔다.
 */
interface CherryPickGateway {

    /** 저장소가 지금 어떤 연산 중인지. 디스크에서 읽으므로 앱을 다시 켜도 복구된다. */
    suspend fun repositoryState(): RepositoryState

    /**
     * [commits] 를 **오래된 것부터** 정렬해 돌려준다.
     *
     * 정렬을 domain 이 하지 않는 이유는 커밋 그래프 순서가 Git 질의이기 때문이다. "이력 순서로
     * 적용한다" 는 규칙은 [CherryPickService] 에 있고, 그 순서를 아는 일은 여기에 있다.
     *
     * @throws UndineException.NotFound 커밋을 찾을 수 없을 때
     */
    suspend fun orderOldestFirst(commits: List<CommitId>): List<CommitId>

    /**
     * [commit] 의 변경을 현재 브랜치에 적용한다.
     *
     * @param recordOrigin true 면 커밋 메시지에 원본 해시를 남긴다 (`git cherry-pick -x` 상당).
     *   나중에 "이 커밋이 어디서 왔는지" 를 추적하는 유일한 단서다.
     */
    suspend fun apply(commit: CommitId, recordOrigin: Boolean): CherryPickStep

    /**
     * 지금 적용하려다 멈춘 커밋(`CHERRY_PICK_HEAD`). 진행 중이 아니거나 읽을 수 없으면 null 이다.
     *
     * 화면이 "무엇을 적용하다 멈췄는지" 를 보여주는 근거다 — 그것을 모르면 사용자는 해결할 충돌이
     * 어느 커밋의 것인지 알 수 없다.
     */
    suspend fun stoppedAt(): CommitId?

    /** 충돌을 해결하고 인덱스에 올린 뒤 멈춘 커밋을 마무리한다. */
    suspend fun continueAfterResolve(): CherryPickStep

    /**
     * 진행 중인 cherry-pick 을 버리고 시작 전 상태로 되돌린다.
     *
     * @param confirmation 사라질 워킹트리 편집을 사용자에게 보여 주고 받은 확인. 실행 직전 다시
     *   대조해, 확인 뒤에 생긴 편집이 있으면 되돌리지 않는다.
     */
    suspend fun abort(confirmation: CherryPickAbortConfirmation)
}
