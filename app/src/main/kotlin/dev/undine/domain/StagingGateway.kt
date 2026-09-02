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
     * [paths] 를 인덱스에 올리고 **같은 임계 구역 안에서** 그 경로만 커밋한다.
     *
     * [stage] 와 [commit] 을 나눠 부르는 것과 다르다. 나눠 부르면 두 호출 사이가 열려 있어
     * ① 그 사이에 취소되면 인덱스에만 올라간 부분 상태(서브모듈이라면 gitlink 만)가 남고
     * ② 그 사이에 앱의 다른 경로가 올린 변경이 이 커밋에 섞여 들어간다.
     *
     * 커밋 대상은 [paths] 로 한정한다 — 이미 인덱스에 있던 다른 변경은 올라간 채 그대로 남는다.
     *
     * **실패해도 부분 상태를 남기지 않는다.** stage 가 끝난 뒤 커밋이 실패하면 시작 시점 인덱스로
     * 되돌린 뒤 원인을 올린다 — 취소 창만 막고 실패 창을 열어 두면 서브모듈에서 gitlink 만 올라간
     * 인덱스가 남는다.
     *
     * @throws UndineException.NothingToCommit [paths] 에 커밋할 변경이 없을 때
     * @throws UndineException.AuthorNotConfigured 작성자 정보가 설정돼 있지 않을 때
     */
    suspend fun stageAndCommit(paths: List<String>, message: String): CommitResult

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
