package dev.undine.domain

/**
 * 브랜치·태그 조회와 브랜치 조작. ref 이름 형식 검증([UndineException.InvalidRefName])은
 * 이 Gateway 를 구현하는 티켓이 소유한다.
 */
interface RefGateway {

    suspend fun listBranches(): List<Branch>

    suspend fun listTags(): List<Tag>

    suspend fun createBranch(name: RefName, at: CommitId): Branch

    suspend fun renameBranch(from: RefName, to: RefName)

    suspend fun deleteBranch(name: RefName, force: Boolean): DeleteBranchResult

    suspend fun checkout(ref: RefName, force: Boolean)
}
