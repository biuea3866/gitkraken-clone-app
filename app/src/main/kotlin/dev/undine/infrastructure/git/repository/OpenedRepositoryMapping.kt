package dev.undine.infrastructure.git.repository

import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import java.io.File
import org.eclipse.jgit.lib.RepositoryState as JGitRepositoryState

/** revert 진행 중임을 나타내는 파일. JGit 도 이 파일로 revert 상태를 판정한다. */
private const val REVERT_HEAD_FILE = "REVERT_HEAD"

private val MERGING_STATES = setOf(
    JGitRepositoryState.MERGING,
    JGitRepositoryState.MERGING_RESOLVED,
)

private val CHERRY_PICKING_STATES = setOf(
    JGitRepositoryState.CHERRY_PICKING,
    JGitRepositoryState.CHERRY_PICKING_RESOLVED,
)

private val REBASING_STATES = setOf(
    JGitRepositoryState.REBASING,
    JGitRepositoryState.REBASING_REBASING,
    JGitRepositoryState.REBASING_MERGE,
    JGitRepositoryState.REBASING_INTERACTIVE,
    JGitRepositoryState.APPLY,
)

/**
 * 열린 저장소의 HEAD 상태를 도메인 [OpenedRepository] 로 옮긴다.
 *
 * [OpenedRepository.currentBranch] 는 **완전한 참조 이름**(`refs/heads/main`)이다 — 짧은 이름은
 * 원격 추적 브랜치와 구분되지 않는다. 표시용 축약은 presentation 이 한다.
 * detached HEAD 면 브랜치가 없으므로 null 이다.
 */
internal fun Repository.toOpenedRepository(): OpenedRepository {
    val head = exactRef(Constants.HEAD)
    return OpenedRepository(
        state = resolveState(head),
        currentBranch = if (head != null && head.isSymbolic) RefName(head.target.name) else null,
    )
}

/**
 * 진행 중인 연산(revert·cherry-pick·merge·rebase)이 HEAD 조건보다 우선한다 — "지금 무엇을 할 수 있는가" 를
 * 후행 티켓이 이 값으로 판단하므로, 충돌을 끝내야 하는 상태를 정상으로 보이게 하면 안 된다.
 *
 * bisect 는 계약에 아직 없어 [RepositoryState.NORMAL] 로 떨어진다 — 그 상태를 필요로 하는 티켓이
 * enum 과 함께 추가한다.
 */
private fun Repository.resolveState(head: Ref?): RepositoryState = when {
    isRevertInProgress() -> RepositoryState.REVERTING
    repositoryState in CHERRY_PICKING_STATES -> RepositoryState.CHERRY_PICKING
    repositoryState in MERGING_STATES -> RepositoryState.MERGING
    repositoryState in REBASING_STATES -> RepositoryState.REBASING
    head == null || head.objectId == null -> RepositoryState.EMPTY
    !head.isSymbolic -> RepositoryState.DETACHED
    else -> RepositoryState.NORMAL
}

private fun Repository.isRevertInProgress(): Boolean =
    directory?.let { File(it, REVERT_HEAD_FILE).exists() } == true
