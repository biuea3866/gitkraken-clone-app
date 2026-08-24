package dev.undine.domain.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus

private const val NOTHING_TO_PICK = "적용할 커밋이 없습니다"
private const val NOT_IN_PROGRESS = "cherry-pick 이 진행 중이 아닙니다"
private const val UNCONFIRMED_DISCARD = "확인한 뒤에 생긴 편집이 있어 중단하지 않았습니다"
private const val STOPPED_COMMIT_UNKNOWN = "멈춘 커밋을 읽을 수 없습니다"

/**
 * cherry-pick 의 규칙을 갖는 도메인 서비스. 실행은 [CherryPickGateway] 가 한다.
 *
 * 규칙은 세 가지다.
 * - **시작 전 워킹트리가 깨끗해야 한다.** 더티 상태에서 시작하면 사용자의 편집과 cherry-pick 충돌이
 *   뒤섞여 구분할 수 없다. 계속·중단은 이 검사를 하지 않는다 — 충돌을 해결한 워킹트리는 항상 더티다.
 * - **선택 순서가 아니라 이력 순서(오래된 것부터)로 적용한다.** 클릭한 순서대로 적용하면 뒤 커밋이
 *   앞 커밋을 전제로 할 때 불필요한 충돌이 난다.
 * - **중단은 사라질 편집을 확인받은 뒤에만.** 확인 뒤에 편집이 늘었으면 되돌리지 않는다.
 */
class CherryPickService(
    private val repositoryGateway: RepositoryGateway,
    private val cherryPickGateway: CherryPickGateway,
) {

    /**
     * [commits] 를 이력 순서로 적용한다.
     *
     * 충돌이 나면 **그 자리에서 멈춘다** — 남은 커밋을 계속 적용하면 사용자가 해결해야 할 충돌이
     * 여러 겹으로 쌓인다.
     *
     * @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있어 시작하지 않았을 때
     * @throws UndineException.StateViolation [commits] 가 비었을 때
     */
    suspend fun cherryPick(commits: List<CommitId>, recordOrigin: Boolean): CherryPickResult {
        if (commits.isEmpty()) throw UndineException.StateViolation(NOTHING_TO_PICK)
        requireCleanWorkingTree()
        val created = mutableListOf<CommitId>()
        cherryPickGateway.orderOldestFirst(commits).forEach { commit ->
            when (val step = cherryPickGateway.apply(commit, recordOrigin)) {
                is CherryPickStep.Created -> created += step.commit
                CherryPickStep.Empty -> Unit
                is CherryPickStep.Conflicted ->
                    return CherryPickResult.Conflicted(step.paths, commit, created.toList())
            }
        }
        return if (created.isEmpty()) CherryPickResult.AlreadyApplied else CherryPickResult.Applied(created)
    }

    /**
     * 충돌을 해결한 뒤 멈춘 커밋을 마무리한다.
     *
     * 남은 커밋을 자동으로 이어 적용하지 않는다 — 어디까지 갔는지 화면이 알고 있으므로, 다음 커밋은
     * 사용자가 다시 요청한다. 여기서 이어가면 [cherryPick] 의 "충돌에서 멈춘다" 규칙과 어긋난다.
     *
     * @throws UndineException.StateViolation cherry-pick 이 진행 중이 아닐 때
     */
    suspend fun continueAfterResolve(): CherryPickResult {
        requireInProgress()
        // 멈춘 커밋은 이어가기 **전에** 읽는다 — 성공하면 그 참조가 지워져 더 이상 알 수 없다.
        val stopped = cherryPickGateway.stoppedAt()
        return when (val step = cherryPickGateway.continueAfterResolve()) {
            is CherryPickStep.Created -> CherryPickResult.Applied(listOf(step.commit))
            CherryPickStep.Empty -> CherryPickResult.AlreadyApplied
            is CherryPickStep.Conflicted -> CherryPickResult.Conflicted(
                paths = step.paths,
                // 이어가다 다시 충돌했다면 여전히 같은 커밋에서 멈춰 있다.
                stoppedAt = stopped ?: throw UndineException.StateViolation(STOPPED_COMMIT_UNKNOWN),
                created = emptyList(),
            )
        }
    }

    /**
     * 진행 중인 cherry-pick 을 되돌린다.
     *
     * @throws UndineException.StateViolation 진행 중이 아니거나, 확인 뒤에 생긴 편집이 있을 때
     */
    suspend fun abort(confirmation: CherryPickAbortConfirmation) {
        requireInProgress()
        requireConfirmedDiscard(confirmation)
        cherryPickGateway.abort(confirmation)
    }

    private suspend fun requireCleanWorkingTree() {
        val status = repositoryGateway.status()
        if (!status.isClean) throw UndineException.DirtyWorkingTree(status.dirtyPaths())
    }

    private suspend fun requireInProgress() {
        if (cherryPickGateway.repositoryState() != RepositoryState.CHERRY_PICKING) {
            throw UndineException.StateViolation(NOT_IN_PROGRESS)
        }
    }

    /**
     * 확인한 목록이 지금 사라질 편집을 다 담고 있는지 본다. 확인 뒤에 편집이 더 생겼다면 사용자는
     * 그 파일이 사라진다는 것을 **모르고** 확인한 것이므로 되돌리지 않는다.
     */
    private suspend fun requireConfirmedDiscard(confirmation: CherryPickAbortConfirmation) {
        val confirmed = confirmation.discardedPaths.toSet()
        val unconfirmed = repositoryGateway.status().discardedByAbort().filterNot { it in confirmed }
        if (unconfirmed.isNotEmpty()) {
            throw UndineException.StateViolation("$UNCONFIRMED_DISCARD: ${unconfirmed.joinToString()}")
        }
    }
}

/** 화면이 "무엇 때문에 시작하지 못했는지" 를 보여줄 수 있도록 더티한 경로를 한 목록으로 모은다. */
private fun WorkingTreeStatus.dirtyPaths(): List<String> =
    (staged.map { it.path } + unstaged.map { it.path } + untracked + conflicted).distinct().sorted()

/**
 * 중단이 지우는 경로. 추적되지 않는 파일은 제외한다 — 되돌리기가 그 파일을 건드리지 않아 사용자에게
 * "사라진다" 고 확인받을 대상이 아니다.
 */
private fun WorkingTreeStatus.discardedByAbort(): List<String> =
    (staged.map { it.path } + unstaged.map { it.path } + conflicted).distinct().sorted()
