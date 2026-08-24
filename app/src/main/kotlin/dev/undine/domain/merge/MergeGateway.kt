package dev.undine.domain.merge

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException

/**
 * 병합·리베이스의 **실행**을 맡는 외부 Git 접근 계약. 규칙(시작 전 검사·진행 중 검사)은
 * [MergeService] 가 갖고, 여기에는 "무엇을 실행하는가" 만 둔다. 구현은 `MergeGatewayImpl` 이다.
 *
 * merge 계열과 rebase 계열을 한 메서드로 합치지 않는다 — 무엇이 진행 중인지에 따라 결과 타입과
 * 되돌리는 방법이 다르고, 합치면 호출부가 그 차이를 다시 분기해야 한다.
 *
 * **파괴적 연산은 확인 토큰을 인자로 받는다.** 확인을 [MergeService] 안에서만 검사하면 계약상
 * Gateway 를 직접 호출하는 경로가 그 검사를 통째로 건너뛴다 — 토큰을 여기까지 끌어와야 "확인 없이는
 * 지울 수 없다" 가 계약이 된다. 구현은 토큰이 **지금** 사라질 대상과 맞는지 실행 직전 같은 임계구역에서
 * 다시 확인한다 (검사와 실행 사이에 상태가 바뀔 수 있다).
 */
interface MergeGateway {

    /**
     * 저장소가 지금 어떤 연산 중인지 읽는다. **디스크에서 읽으므로 앱을 다시 열어도 복구된다** —
     * 진행 중 상태를 메모리에 들고 있으면 재시작 후 사용자가 빠져나올 방법을 잃는다.
     */
    suspend fun repositoryState(): RepositoryState

    /**
     * [target] 을 현재 브랜치로 병합한다.
     *
     * [allowFastForward] 가 true 면 빨리 감기가 가능할 때 병합 커밋 없이 HEAD 만 옮기고,
     * false 면 항상 병합 커밋을 만든다.
     *
     * @throws UndineException.NotFound [target] 을 ref 로도 커밋으로도 찾을 수 없을 때
     */
    suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult

    /** 충돌을 해결하고 인덱스에 올린 뒤 병합 커밋을 만든다. 미해결 파일이 남아 있으면 [MergeResult.Conflicted] 다. */
    suspend fun continueMerge(): MergeResult

    /**
     * 진행 중인 병합을 버리고 `ORIG_HEAD` 로 시작 전 상태를 복구한다.
     *
     * @param confirmation 사라질 워킹트리 편집을 사용자에게 보여 주고 받은 확인. 실행 직전 다시
     *   대조해, 확인 뒤에 생긴 편집이 있으면 중단하지 않는다.
     */
    suspend fun abortMerge(confirmation: AbortConfirmation)

    /**
     * 현재 브랜치를 [target] 위로 재배치한다.
     *
     * @throws UndineException.NotFound [target] 을 ref 로도 커밋으로도 찾을 수 없을 때
     */
    suspend fun rebase(target: RefName): RebaseResult

    /** 충돌을 해결하고 인덱스에 올린 뒤 남은 커밋을 이어서 적용한다. */
    suspend fun continueRebase(): RebaseResult

    /**
     * 리베이스가 멈춰 있는 **지금 적용 중인 커밋**. 진행 중이 아니거나 멈춘 커밋을 읽을 수 없으면 null 이다.
     *
     * [MergeService] 가 건너뛰기 확인([SkipConfirmation])이 낡지 않았는지 대조하는 데 쓴다 —
     * 화면이 보여 준 커밋과 지금 사라질 커밋이 다르면 사용자는 **다른 커밋이 사라진다는 것을 모르고**
     * 확인한 것이다. 진행 중 상태와 마찬가지로 디스크에서 읽어 앱 재시작 뒤에도 대조가 성립한다.
     */
    suspend fun rebasingCommit(): CommitId?

    /**
     * 지금 적용 중인 커밋을 결과 이력에서 빼고 다음 커밋으로 넘어간다. 리베이스 전용이다.
     *
     * @param confirmation 사라질 커밋을 사용자에게 보여 주고 받은 확인. 실행 직전 다시 대조해,
     *   그 사이 다른 커밋에서 멈춰 있게 됐으면 건너뛰지 않는다.
     */
    suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult

    /**
     * 진행 중인 리베이스를 버리고 `ORIG_HEAD` 로 시작 전 상태를 복구한다.
     *
     * @param confirmation [abortMerge] 와 같은 확인. 실행 직전 다시 대조한다.
     */
    suspend fun abortRebase(confirmation: AbortConfirmation)
}
