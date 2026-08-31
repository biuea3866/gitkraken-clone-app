package dev.undine.infrastructure.git.merge

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.infrastructure.git.ref.baselineHeld
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.api.MergeResult as JGitMergeResult
import org.eclipse.jgit.api.RebaseResult as JGitRebaseResult

private const val NOTHING_TO_COMMIT = "충돌을 해결한 결과에 커밋할 변경이 없습니다 — 건너뛰기로 진행하세요"

/**
 * JGit 병합·리베이스 결과를 도메인 결과로 옮긴다.
 *
 * 이 매핑의 핵심은 **세 종류를 뭉뚱그리지 않는 것**이다.
 * - 충돌 → 결과 타입([MergeResult.Conflicted] · [RebaseResult.Conflicted]). 실패가 아니다.
 * - 사용자가 고칠 수 있는 실패(커밋되지 않은 변경) → [UndineException.DirtyWorkingTree]
 * - 그 밖의 다루지 못한 상태 → [UndineException.GitOperationFailed] (작업명에 JGit 상태 이름을 남긴다)
 */
internal fun JGitMergeResult.toDomain(git: Git, operation: String): MergeResult = when (mergeStatus) {
    JGitMergeResult.MergeStatus.FAST_FORWARD -> git.repository.merged(operation, fastForward = true)

    JGitMergeResult.MergeStatus.MERGED -> git.repository.merged(operation, fastForward = false)

    JGitMergeResult.MergeStatus.ALREADY_UP_TO_DATE -> MergeResult.AlreadyUpToDate

    JGitMergeResult.MergeStatus.CONFLICTING -> MergeResult.Conflicted(git.unresolvedPaths())

    else -> throw mergeFailure(operation)
}

/**
 * 병합이 **시작조차 못 한** 실패를 번역한다. 체크아웃 충돌과 실패 경로는 커밋되지 않은 변경이 원인이라
 * 사용자가 정리하면 해결되므로 [UndineException.DirtyWorkingTree] 다.
 */
private fun JGitMergeResult.mergeFailure(operation: String): UndineException = when (mergeStatus) {
    JGitMergeResult.MergeStatus.CHECKOUT_CONFLICT ->
        UndineException.DirtyWorkingTree(checkoutConflicts.orEmpty().sorted())

    JGitMergeResult.MergeStatus.FAILED ->
        UndineException.DirtyWorkingTree(failingPaths?.keys?.sorted().orEmpty())

    else -> UndineException.GitOperationFailed("$operation(${mergeStatus.name})")
}

internal fun JGitRebaseResult.toDomain(git: Git, operation: String): RebaseResult = when (status) {
    JGitRebaseResult.Status.OK, JGitRebaseResult.Status.FAST_FORWARD -> git.repository.rebased(operation)

    JGitRebaseResult.Status.UP_TO_DATE -> RebaseResult.AlreadyUpToDate

    JGitRebaseResult.Status.STOPPED -> RebaseResult.Conflicted(git.unresolvedPaths())

    else -> throw rebaseFailure(operation)
}

/**
 * 리베이스가 진행하지 못한 실패를 번역한다. 커밋되지 않은 변경과 체크아웃 충돌은 사용자가 정리할 수 있고,
 * 커밋할 것이 없는 상태는 건너뛰기로 빠져나가야 하므로 예상 못 한 실패와 구분한다.
 */
private fun JGitRebaseResult.rebaseFailure(operation: String): UndineException = when (status) {
    JGitRebaseResult.Status.UNCOMMITTED_CHANGES ->
        UndineException.DirtyWorkingTree(uncommittedChanges.orEmpty().sorted())

    JGitRebaseResult.Status.CONFLICTS ->
        UndineException.DirtyWorkingTree(conflicts.orEmpty().sorted())

    JGitRebaseResult.Status.NOTHING_TO_COMMIT -> UndineException.StateViolation(NOTHING_TO_COMMIT)

    else -> UndineException.GitOperationFailed("$operation(${status.name})")
}

/**
 * 인덱스에 남은 미해결 경로. JGit 결과 객체의 목록은 연산에 따라 비어 있어서,
 * 저장소 인덱스를 정본으로 삼는다.
 */
internal fun Git.unresolvedPaths(): List<String> = status().call().conflicting.sorted()

/**
 * 성공한 병합 결과를 **되돌리기 재료와 함께** 만든다.
 *
 * 시작 지점·기준 상태를 여기서 읽는 것이 요점이다 — 이 함수는 병합을 실행한 것과 **같은 임계
 * 구역** 안에서 불리므로, 호출자가 나중에 읽을 때와 달리 그 사이의 다른 조작이 섞이지 않는다
 * (UND-73). 시작 지점의 정본은 중단(abort)이 쓰는 것과 같은 `ORIG_HEAD` 다 — 두 경로가 서로 다른
 * "시작 전" 을 갖지 않게 한 곳으로 모은다.
 */
private fun Repository.merged(operation: String, fastForward: Boolean): MergeResult =
    MergeResult.Succeeded(
        head = headCommitId(operation),
        fastForward = fastForward,
        previousHead = startPointHeld(),
        baseline = baselineHeld(),
    )

/** [merged] 와 같은 재료를 리베이스 결과에 싣는다 — 계속·건너뛰기도 이 경로를 지난다. */
private fun Repository.rebased(operation: String): RebaseResult =
    RebaseResult.Succeeded(
        head = headCommitId(operation),
        previousHead = startPointHeld(),
        baseline = baselineHeld(),
    )

/**
 * 병합·리베이스를 시작하기 전 HEAD. `ORIG_HEAD` 가 정본이며 중단이 되돌리는 지점과 같다.
 * 커밋이 하나도 없던 저장소는 남길 지점이 없어 null 이다.
 */
internal fun Repository.startPointHeld(): CommitId? = readOrigHead()?.let { CommitId.of(it.name) }

private fun Repository.headCommitId(operation: String): CommitId {
    val head = resolve(Constants.HEAD) ?: throw UndineException.GitOperationFailed(operation)
    return CommitId.of(head.name)
}
