package dev.undine.domain

/** 스테이징 조작과 커밋 생성. */
interface StagingGateway {

    suspend fun stage(paths: List<String>)

    suspend fun unstage(paths: List<String>)

    suspend fun stageHunks(path: String, hunks: List<DiffHunk>)

    suspend fun commit(message: String, amend: Boolean): CommitResult
}
