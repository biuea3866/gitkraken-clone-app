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

    suspend fun checkout(ref: RefName, force: Boolean)
}
