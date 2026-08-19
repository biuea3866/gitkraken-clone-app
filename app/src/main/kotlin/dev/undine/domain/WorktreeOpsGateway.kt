package dev.undine.domain

/**
 * 워킹트리를 되돌리는 연산 — stash·revert·reset.
 * [hardReset] 은 워킹트리를 파괴하므로 [ResetMode] 플래그가 아니라 별도 메서드다.
 */
interface WorktreeOpsGateway {

    suspend fun stashPush(includeUntracked: Boolean): StashEntry

    suspend fun stashList(): List<StashEntry>

    suspend fun stashPop()

    suspend fun stashApply(entry: StashEntry)

    suspend fun stashDrop(entry: StashEntry)

    suspend fun revert(commit: CommitId): RevertResult

    suspend fun reset(commit: CommitId, mode: ResetMode)

    suspend fun hardReset(commit: CommitId)
}
