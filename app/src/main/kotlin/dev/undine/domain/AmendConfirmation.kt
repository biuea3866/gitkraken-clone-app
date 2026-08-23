package dev.undine.domain

/**
 * amend 실행 허가. [AmendPreflight] 로 대상을 확인한 뒤에만 만들 수 있다.
 *
 * sealed 인 이유: "확인이 필요 없다" 와 "이 커밋을 확인했다" 는 서로 다르게 검증해야 하는 값이고,
 * Boolean 으로 뭉개면 확인 없는 amend 가 조용히 통과한다.
 */
sealed interface AmendConfirmation {

    /** 원격에 없는 커밋이라 사용자 확인이 필요 없다. */
    data object NotRequired : AmendConfirmation

    /** 사용자가 [target] 을 고쳐 쓰겠다고 명시적으로 확인했다. */
    data class ConfirmedRemoteTarget(val target: CommitId) : AmendConfirmation

    /**
     * 실행 직전 저장소 상태에 이 허가가 여전히 유효한지 판단한다.
     * 판단 기준이 도메인 규칙이므로 JGit 구현이 아니라 여기에 둔다 —
     * Gateway 구현은 [target]·[existsOnRemote] 를 **다시 읽어** 이 함수에 넘긴다.
     *
     * 확인된 대상은 [existsOnRemote] 와 무관하게 대상 일치만 본다. 조회와 실행 사이에 HEAD 가
     * 바뀌었다면 사용자가 본 커밋과 고칠 커밋이 다르므로, 원격 포함 여부와 상관없이 낡은 허가다.
     *
     * @throws UndineException.AmendConfirmationRequired 허가가 없거나 낡았을 때
     */
    fun validateFor(target: CommitId, existsOnRemote: Boolean) {
        val reason = when (this) {
            NotRequired ->
                UndineException.AmendConfirmationRequired.Reason.NOT_CONFIRMED
                    .takeIf { existsOnRemote }

            is ConfirmedRemoteTarget ->
                UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH
                    .takeIf { this.target != target }
        }
        if (reason != null) throw UndineException.AmendConfirmationRequired(target, reason)
    }
}
