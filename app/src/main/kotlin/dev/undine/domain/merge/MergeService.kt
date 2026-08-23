package dev.undine.domain.merge

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus

private const val MERGE_NOT_IN_PROGRESS = "병합이 진행 중이 아닙니다"
private const val REBASE_NOT_IN_PROGRESS = "리베이스가 진행 중이 아닙니다"
private const val NOTHING_TO_ABORT = "중단할 병합·리베이스가 진행 중이 아닙니다"
private const val UNCONFIRMED_DISCARD = "확인한 뒤에 생긴 편집이 있어 중단하지 않았습니다"
private const val SKIP_TARGET_UNKNOWN = "건너뛸 커밋을 읽을 수 없어 건너뛰지 않았습니다"
private const val STALE_SKIP_CONFIRMATION = "확인한 커밋과 지금 건너뛸 커밋이 달라 건너뛰지 않았습니다"

/**
 * 병합·리베이스의 **규칙**을 갖는다. Git 실행은 [MergeGateway] 가 하고, 여기서는
 * "지금 이 연산을 시작해도 되는가" 만 판단한다.
 *
 * 규칙은 두 가지다.
 * - **시작 전 워킹트리가 깨끗해야 한다.** 더티 상태에서 시작하면 사용자의 편집과 병합 충돌이 뒤섞여
 *   구분할 수 없다. 계속·중단·건너뛰기는 이 검사를 하지 않는다 — 충돌을 해결한 워킹트리는 항상 더티다.
 * - **계속·중단·건너뛰기는 진행 중일 때만 가능하다.** 진행 중이 아닌데 호출하면
 *   [UndineException.StateViolation] 이다. 건너뛰기는 리베이스 전용이라 병합 진행 중에도 상태 위반이다.
 *
 * **되돌릴 수 없다.** 리베이스는 커밋을 새로 쓰므로 [abort] 없이 끝낸 뒤에는 원래 커밋으로 돌아갈 수
 * 없다. Undo 스택(UND-38)에는 아직 기록하지 않는다 — 그 계약은 이 티켓 범위 밖이다.
 */
class MergeService(
    private val repositoryGateway: RepositoryGateway,
    private val mergeGateway: MergeGateway,
) {

    /** @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있어 시작하지 않았을 때 */
    suspend fun merge(target: RefName, allowFastForward: Boolean = true): MergeResult {
        requireCleanWorkingTree()
        return mergeGateway.merge(target, allowFastForward)
    }

    /** @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있어 시작하지 않았을 때 */
    suspend fun rebase(target: RefName): RebaseResult {
        requireCleanWorkingTree()
        return mergeGateway.rebase(target)
    }

    /** @throws UndineException.StateViolation 병합이 진행 중이 아닐 때 */
    suspend fun continueMerge(): MergeResult {
        requireInProgress(RepositoryState.MERGING, MERGE_NOT_IN_PROGRESS)
        return mergeGateway.continueMerge()
    }

    /** @throws UndineException.StateViolation 리베이스가 진행 중이 아닐 때 */
    suspend fun continueRebase(): RebaseResult {
        requireInProgress(RepositoryState.REBASING, REBASE_NOT_IN_PROGRESS)
        return mergeGateway.continueRebase()
    }

    /**
     * 건너뛰기는 리베이스 전용이다 — 병합에는 "지금 적용 중인 커밋" 이라는 개념이 없다.
     *
     * **파괴적이다** — 건너뛴 커밋의 변경은 결과 이력에서 사라지고 되돌릴 수 없다. 그래서
     * [confirmation] 을 필수로 받는다([SkipConfirmation]): 화면이 사라질 커밋을 보여 주고 확인을
     * 받지 않으면 이 호출을 만들 수 없고, 확인한 커밋과 지금 멈춰 있는 커밋이 다르면 건너뛰지 않는다.
     * 최종 대조는 같은 토큰을 받은 Gateway 가 자기 임계구역 안에서 한 번 더 한다.
     *
     * @throws UndineException.StateViolation 리베이스가 진행 중이 아니거나(병합 진행 중 포함),
     * 건너뛸 커밋을 읽을 수 없거나, 확인이 지금 건너뛸 커밋과 어긋날 때
     */
    suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult {
        requireInProgress(RepositoryState.REBASING, REBASE_NOT_IN_PROGRESS)
        requireConfirmedSkipTarget(confirmation)
        return mergeGateway.skipRebaseCommit(confirmation)
    }

    /**
     * 진행 중인 병합 또는 리베이스를 시작 전 상태로 되돌린다. revert 중단은 이 서비스 범위가 아니다.
     *
     * **파괴적이다** — 충돌을 해결하며 쓴 워킹트리 편집은 사라지고 되돌릴 수 없다. 그래서
     * [confirmation] 을 필수로 받는다([AbortConfirmation]): 화면이 사라질 편집을 보여 주고 확인을
     * 받지 않으면 이 호출을 만들 수 없고, 확인한 뒤에 편집이 더 생겼으면 시작하지 않는다.
     * 여기의 검사는 **빠른 거절**이고, 실행 직전의 최종 대조는 같은 토큰을 받은 Gateway 가 자기
     * 임계구역 안에서 한 번 더 한다 — 검사와 실행 사이에도 편집이 생길 수 있다.
     *
     * @throws UndineException.StateViolation 병합·리베이스가 진행 중이 아니거나, 확인하지 않은 편집이
     * 남아 있을 때
     */
    suspend fun abort(confirmation: AbortConfirmation) {
        requireConfirmedDiscard(confirmation)
        when (mergeGateway.repositoryState()) {
            RepositoryState.MERGING -> mergeGateway.abortMerge(confirmation)
            RepositoryState.REBASING -> mergeGateway.abortRebase(confirmation)
            else -> throw UndineException.StateViolation(NOTHING_TO_ABORT)
        }
    }

    private suspend fun requireCleanWorkingTree() {
        val status = repositoryGateway.status()
        if (!status.isClean) throw UndineException.DirtyWorkingTree(status.dirtyPaths())
    }

    /**
     * 사용자가 확인한 목록이 지금 사라질 편집을 다 담고 있는지 본다. 확인 뒤에 편집이 더 생겼다면
     * 사용자는 그 파일이 사라진다는 것을 **모르고** 확인한 것이므로 시작하지 않는다.
     */
    private suspend fun requireConfirmedDiscard(confirmation: AbortConfirmation) {
        val confirmed = confirmation.discardedPaths.toSet()
        val unconfirmed = repositoryGateway.status().discardedByAbort().filterNot { it in confirmed }
        if (unconfirmed.isNotEmpty()) {
            throw UndineException.StateViolation("$UNCONFIRMED_DISCARD: ${unconfirmed.joinToString()}")
        }
    }

    /**
     * 사용자가 확인한 커밋이 지금 사라질 커밋과 같은지 본다. 다르다면 사용자는 **다른 커밋이 사라진다는
     * 것을 모르고** 확인한 것이므로 건너뛰지 않는다. 대상을 읽을 수 없을 때도 마찬가지로 멈춘다 —
     * 무엇이 사라지는지 대조할 수 없는 채로 파괴적 연산을 실행하지 않는다.
     */
    private suspend fun requireConfirmedSkipTarget(confirmation: SkipConfirmation) {
        val skipping = mergeGateway.rebasingCommit()
            ?: throw UndineException.StateViolation(SKIP_TARGET_UNKNOWN)
        if (skipping != confirmation.skippedCommit) {
            throw UndineException.StateViolation(
                "$STALE_SKIP_CONFIRMATION: 확인=${confirmation.skippedCommit}, 대상=$skipping",
            )
        }
    }

    private suspend fun requireInProgress(expected: RepositoryState, detail: String) {
        if (mergeGateway.repositoryState() != expected) throw UndineException.StateViolation(detail)
    }
}

/** 화면이 "무엇 때문에 시작하지 못했는지" 를 보여줄 수 있도록 더티한 경로를 한 목록으로 모은다. */
private fun WorkingTreeStatus.dirtyPaths(): List<String> =
    (staged.map { it.path } + unstaged.map { it.path } + untracked + conflicted).distinct().sorted()

/**
 * 중단이 지우는 경로. 추적되지 않는 파일은 제외한다 — 중단의 `reset --hard` 는 그 파일을 건드리지 않아
 * 사용자에게 "사라진다" 고 확인받을 대상이 아니다.
 */
private fun WorkingTreeStatus.discardedByAbort(): List<String> =
    (staged.map { it.path } + unstaged.map { it.path } + conflicted).distinct().sorted()
