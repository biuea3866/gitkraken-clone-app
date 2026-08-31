package dev.undine.domain

/**
 * 브랜치·태그 조회와 브랜치 조작. ref 이름 형식 검증([UndineException.InvalidRefName])은
 * 이 Gateway 를 구현하는 티켓이 소유한다.
 */
interface RefGateway {

    suspend fun listBranches(): List<Branch>

    suspend fun listTags(): List<Tag>

    /**
     * 태그를 만든다. [message] 가 null 이면 lightweight, 있으면 annotated 태그다.
     * 이름 변경은 제공하지 않는다 — git 에서 태그 rename 은 삭제+재생성이라
     * 이미 push 된 태그에서는 위험하다.
     */
    suspend fun createTag(name: RefName, at: CommitId, message: String?): Tag

    suspend fun deleteTag(name: RefName)

    suspend fun createBranch(name: RefName, at: CommitId): Branch

    suspend fun renameBranch(from: RefName, to: RefName)

    suspend fun deleteBranch(name: RefName, force: Boolean): DeleteBranchResult

    /**
     * [ref] 로 체크아웃하고 **옮기기 직전 위치**를 결과로 준다.
     *
     * 이전 위치를 결과에 싣는 이유는 되돌리기를 기록하는 호출자가 그것을 스스로 읽을 수 없기
     * 때문이다 — 체크아웃 뒤에는 이미 사라졌고, 전에 읽으면 그 읽기와 체크아웃 사이의 다른 이동을
     * 놓친다 (UND-73). 실패는 결과가 아니라 예외이므로 이 약속의 대상이 아니다.
     */
    suspend fun checkout(ref: RefName, force: Boolean): CheckoutResult

    /**
     * [branch] 가 **[expected] 를 가리키고 있을 때만** [to] 로 옮긴다 (조건부 갱신).
     *
     * 화면이 본 스냅샷과 실제 ref 가 어긋난 사이 다른 경로가 그 브랜치를 옮겼다면 덮어쓰지 않는다 —
     * 강제로 옮기면 그 ref 로만 도달하던 커밋을 잃고 되돌릴 방법이 없다. 검사와 갱신은 ref-update
     * 잠금 안에서 한 번에 일어난다.
     *
     * **현재 체크아웃된 브랜치는 거부한다.** 포인터만 옮기면 워킹트리가 HEAD 와 어긋난 채 남는다 —
     * 그 경우는 [WorktreeOpsGateway.hardResetBranch] 가 워킹트리 동기화까지 맡는다.
     *
     * 결과는 **이 갱신과 같은 임계 구역에서 캡처한 변경 직후 [RepositoryBaseline]** 이다. 되돌리기를
     * 기록하는 호출자가 그 값을 스스로 읽지 않게 하려는 것이다 (UND-73). 실패는 결과가 아니라
     * 예외이므로 이 약속의 대상이 아니다.
     *
     * @throws UndineException.StateViolation 실제 target 이 [expected] 와 다를 때 · 현재 브랜치일 때
     * @throws UndineException.NotFound 브랜치나 [to] 커밋이 없을 때
     */
    suspend fun moveBranch(branch: RefName, to: CommitId, expected: CommitId): RepositoryBaseline

    /**
     * [tag] 를 [moveBranch] 와 같은 조건부 규칙으로 [to] 로 옮긴다.
     *
     * **annotated 태그는 거부한다.** 태그 ref 를 커밋으로 다시 겨누면 태그 객체에 담긴 메시지와
     * tagger 가 사라지고 되돌릴 수 없다 — 이 앱이 태그 rename 을 제공하지 않는 것과 같은 이유다.
     *
     * [moveBranch] 와 같이 **변경 직후 [RepositoryBaseline]** 을 결과로 준다.
     *
     * @throws UndineException.StateViolation 실제 target 이 [expected] 와 다를 때 · annotated 태그일 때
     * @throws UndineException.NotFound 태그나 [to] 커밋이 없을 때
     */
    suspend fun moveTag(tag: RefName, to: CommitId, expected: CommitId): RepositoryBaseline
}
