package dev.undine.infrastructure.git.worktreeops

import dev.undine.domain.CommitId
import dev.undine.domain.ResetMode
import dev.undine.domain.RevertResult
import dev.undine.domain.StashEntry
import dev.undine.domain.UndineException
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.infrastructure.git.repository.GitAccess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException

private const val OPERATION_STASH_PUSH = "worktreeops.stashPush"
private const val OPERATION_STASH_LIST = "worktreeops.stashList"
private const val OPERATION_STASH_POP = "worktreeops.stashPop"
private const val OPERATION_STASH_APPLY = "worktreeops.stashApply"
private const val OPERATION_STASH_DROP = "worktreeops.stashDrop"
private const val OPERATION_REVERT = "worktreeops.revert"
private const val OPERATION_RESET = "worktreeops.reset"
private const val OPERATION_HARD_RESET = "worktreeops.hardReset"

/** 새로 만든 stash 는 항상 `stash@{0}` 이다. */
private const val NEWEST_STASH_INDEX = 0

/** JGit 은 추적되지 않는 파일을 포함할 때만 stash 커밋에 3번째 부모를 붙인다. */
private const val UNTRACKED_STASH_PARENT_COUNT = 3

/**
 * [WorktreeOpsGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 `Dispatchers.IO` 전환을 그 경계가 책임진다. 이 구현은 락도 디스패처도
 * 다시 걸지 않고, 핸들 수명도 소유하지 않는다(닫지 않는다).
 * 직접 여는 JGit 자원([Git]·[RevWalk])만 `use {}` 로 닫는다.
 */
class WorktreeOpsGatewayImpl(private val gitAccess: GitAccess) : WorktreeOpsGateway {

    /**
     * 워킹트리 변경을 stash 로 옮긴다. [includeUntracked] 가 true 면 추적되지 않는 파일까지 가져가므로
     * pop 하기 전까지 그 파일들은 워킹트리에서 사라진다.
     *
     * @throws UndineException.StateViolation stash 로 저장할 변경이 없을 때.
     */
    override suspend fun stashPush(includeUntracked: Boolean): StashEntry =
        gitOperation(OPERATION_STASH_PUSH) { git ->
            val created = git.stashCreate().setIncludeUntracked(includeUntracked).call()
                ?: throw UndineException.StateViolation("stash 로 저장할 변경이 없습니다")
            RevWalk(git.repository).use { walk ->
                walk.parseCommit(created).toStashEntry(NEWEST_STASH_INDEX)
            }
        }

    /** 최신 stash 가 [StashEntry.index] 0 이다. */
    override suspend fun stashList(): List<StashEntry> =
        gitOperation(OPERATION_STASH_LIST) { git ->
            git.stashList().call().mapIndexed { index, commit -> commit.toStashEntry(index) }
        }

    /**
     * 최신 stash 를 적용하고 목록에서 제거한다. 적용이 실패하면 제거하지 않는다.
     *
     * @throws UndineException.NotFound stash 가 0건일 때 — 이름은 대상이 특정되지 않았음을 뜻하는 빈 문자열이다.
     */
    override suspend fun stashPop() {
        gitOperation(OPERATION_STASH_POP) { git ->
            if (git.stashList().call().isEmpty()) {
                throw UndineException.NotFound(UndineException.NotFound.Kind.STASH, "")
            }
            git.stashApply().call()
            git.stashDrop().call()
        }
    }

    /**
     * [entry] 를 워킹트리에 적용하고 목록에는 남긴다.
     *
     * [StashEntry.index] 는 목록 조회 후 새 stash 가 생기면 밀리므로 신뢰하지 않고
     * [StashEntry.target] 커밋으로 대상을 다시 찾는다.
     */
    override suspend fun stashApply(entry: StashEntry) {
        gitOperation(OPERATION_STASH_APPLY) { git ->
            requireStashIndexOf(git, entry)
            git.stashApply().setStashRef(entry.target.value).call()
        }
    }

    /**
     * [entry] 를 목록에서 지운다. **되돌릴 수 없다 — 호출 전 사용자 확인이 필요하다.**
     * 확인 절차는 호출 화면 소유이고, 이 Gateway 는 요청받으면 수행한다.
     */
    override suspend fun stashDrop(entry: StashEntry) {
        gitOperation(OPERATION_STASH_DROP) { git ->
            git.stashDrop().setStashRef(requireStashIndexOf(git, entry)).call()
        }
    }

    /**
     * [commit] 을 되돌리는 새 커밋을 만든다.
     *
     * 충돌은 실패가 아니라 사용자가 이어서 해결할 상태이므로 예외가 아니라
     * [RevertResult.Conflicted] 로 반환하고, 저장소는 revert 진행 중으로 남는다.
     *
     * @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경 때문에 revert 를 적용조차 못 했을 때.
     */
    override suspend fun revert(commit: CommitId): RevertResult =
        gitOperation(OPERATION_REVERT) { git ->
            val command = git.revert().include(git.repository.resolveCommit(commit))
            val newHead = command.call()
            when {
                newHead != null -> RevertResult.Succeeded(CommitId.of(newHead.name))
                command.unmergedPaths.isNotEmpty() -> RevertResult.Conflicted(command.unmergedPaths.toList())
                else -> throw UndineException.DirtyWorkingTree(
                    command.failingResult?.failingPaths?.keys?.toList() ?: emptyList(),
                )
            }
        }

    /** 워킹트리를 보존하는 reset 만 받는다 — hard 는 [ResetMode] 에 없고 [hardReset] 이 따로 있다. */
    override suspend fun reset(commit: CommitId, mode: ResetMode) {
        gitOperation(OPERATION_RESET) { git ->
            git.reset()
                .setMode(mode.toResetType())
                .setRef(git.repository.resolveCommit(commit).name)
                .call()
        }
    }

    /**
     * 워킹트리까지 [commit] 상태로 되돌린다. **커밋하지 않은 편집이 유실되고 되돌릴 수 없다 —
     * 호출 전 사용자 확인이 필요하다.** 플래그로 실수로 켜지지 않도록 별도 메서드로 둔다.
     */
    override suspend fun hardReset(commit: CommitId) {
        gitOperation(OPERATION_HARD_RESET) { git ->
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(git.repository.resolveCommit(commit).name)
                .call()
        }
    }

    /**
     * 공유 핸들 경계([GitAccess.withRepository]) 안에서 [block] 을 돌리고, 예상하지 못한 JGit 실패만
     * [UndineException.GitOperationFailed] 로 번역한다.
     * `CancellationException` 은 애초에 잡지 않으므로 취소가 그대로 전파된다.
     */
    private suspend fun <T> gitOperation(operation: String, block: (Git) -> T): T =
        try {
            gitAccess.withRepository { repository ->
                // Git.wrap 은 공유 Repository 를 닫지 않는다 — 닫는 것은 Git 자신의 자원뿐이다.
                Git.wrap(repository).use(block)
            }
        } catch (failure: GitAPIException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: JGitInternalException) {
            throw UndineException.GitOperationFailed(operation, failure)
        } catch (failure: IOException) {
            throw UndineException.GitOperationFailed(operation, failure)
        }
}

private fun RevCommit.toStashEntry(index: Int): StashEntry = StashEntry(
    index = index,
    message = fullMessage,
    target = CommitId.of(name),
    createdAt = committerIdent.whenAsInstant,
    includedUntracked = parentCount == UNTRACKED_STASH_PARENT_COUNT,
)

/**
 * 40자 해시는 객체가 없어도 [Repository.resolve] 가 그대로 돌려주므로 존재까지 확인한다 —
 * 확인하지 않으면 뒤늦게 JGit 내부 실패로 뭉뚱그려진다.
 */
private fun Repository.resolveCommit(commit: CommitId): ObjectId {
    val objectId = resolve(commit.value)
    val present = objectId != null && newObjectReader().use { reader -> reader.has(objectId) }
    if (objectId == null || !present) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, commit.value)
    }
    return objectId
}

/** stash 목록에서 [entry] 의 현재 위치를 찾는다 — [StashEntry.index] 는 stale 일 수 있다. */
private fun requireStashIndexOf(git: Git, entry: StashEntry): Int {
    val index = git.stashList().call().indexOfFirst { it.name == entry.target.value }
    if (index < 0) {
        throw UndineException.NotFound(UndineException.NotFound.Kind.STASH, entry.target.value)
    }
    return index
}

private fun ResetMode.toResetType(): ResetCommand.ResetType = when (this) {
    ResetMode.SOFT -> ResetCommand.ResetType.SOFT
    ResetMode.MIXED -> ResetCommand.ResetType.MIXED
}
