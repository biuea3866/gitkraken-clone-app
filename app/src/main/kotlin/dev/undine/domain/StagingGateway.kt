package dev.undine.domain

/** 스테이징 조작과 커밋 생성. */
interface StagingGateway {

    suspend fun stage(paths: List<String>)

    suspend fun unstage(paths: List<String>)

    suspend fun stageHunks(path: String, hunks: List<DiffHunk>)

    /**
     * 새 커밋을 만든다. HEAD 를 다시 쓰지 않으므로 확인 인자를 받지 않는다.
     * 기존 커밋을 고치는 것은 [amend] 다 — 파괴적 연산을 Boolean 플래그로 겸하지 않는다.
     */
    suspend fun commit(message: String): CommitResult

    /**
     * amend 대상과 원격 포함 여부를 조회한다. 저장소를 바꾸지 않는다.
     *
     * @throws UndineException.StateViolation HEAD 가 없어 고칠 커밋이 없을 때
     */
    suspend fun inspectAmend(): AmendPreflight

    /**
     * HEAD 커밋을 고쳐 쓴다. [confirmation] 이 [inspectAmend] 로 확인한 대상과 맞아야 실행한다.
     * 구현은 실행 직전 HEAD 와 원격 포함 여부를 다시 검사한다 — 조회와 실행 사이에 저장소가 바뀔 수 있다.
     *
     * @throws UndineException.AmendConfirmationRequired 확인이 없거나 낡았을 때
     * @throws UndineException.StateViolation HEAD 가 없거나 백업 ref 를 남기지 못했을 때
     */
    suspend fun amend(message: String, confirmation: AmendConfirmation): CommitResult
}
