package dev.undine.domain.cherrypick

import dev.undine.domain.CommitId

/**
 * cherry-pick 결과.
 *
 * **충돌과 "이미 적용됨" 은 실패가 아니다.** 예외로 올리면 화면이 다음에 무엇을 해야 하는지 알려줄
 * 수 없다 — 충돌은 해결하고 이어가면 되고, 이미 적용된 변경은 할 일이 없다.
 */
sealed interface CherryPickResult {

    /** 요청한 커밋을 모두 적용했다. [created] 는 새로 만들어진 커밋(적용 순서, 오래된 것부터). */
    data class Applied(val created: List<CommitId>) : CherryPickResult

    /**
     * 적용할 변경이 남아 있지 않았다 — 결과가 빈 커밋이 됐을 상황이다.
     *
     * git 도 이 경우 커밋을 만들지 않는다. 실패로 처리하면 사용자가 "무엇을 고쳐야 하는지" 를 찾게
     * 되지만, 실제로는 고칠 것이 없다.
     */
    data object AlreadyApplied : CherryPickResult

    /**
     * 충돌로 멈췄다. 저장소는 cherry-pick 진행 중으로 남는다.
     *
     * [created] 는 멈추기 **전에** 이미 만들어진 커밋이다 — 여러 커밋을 요청했을 때 어디까지 갔는지
     * 알려주지 않으면 사용자가 중단해도 되는지 판단할 수 없다.
     */
    data class Conflicted(
        val paths: List<String>,
        val stoppedAt: CommitId,
        val created: List<CommitId>,
    ) : CherryPickResult
}
