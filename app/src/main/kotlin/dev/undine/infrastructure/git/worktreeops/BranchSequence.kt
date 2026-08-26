package dev.undine.infrastructure.git.worktreeops

import dev.undine.domain.BranchOperation
import dev.undine.domain.BranchOperationResult
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.infrastructure.git.cherrypick.applyHeld
import dev.undine.infrastructure.git.merge.mergeHeld
import dev.undine.infrastructure.git.merge.rebaseHeld
import dev.undine.infrastructure.git.ref.LOCAL_BRANCH_PREFIX
import dev.undine.infrastructure.git.ref.checkoutHeld
import dev.undine.infrastructure.git.ref.rejectIfDirty
import dev.undine.infrastructure.git.ref.requireCommitObject
import dev.undine.infrastructure.git.ref.requireExpectedTarget
import dev.undine.infrastructure.git.ref.requireRefTarget
import dev.undine.infrastructure.git.ref.updateRefHeld
import dev.undine.infrastructure.git.ref.validatedBranchRef
import dev.undine.infrastructure.git.repository.toOpenedRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository

private const val DETACHED_HEAD_TARGET =
    "detached HEAD 라 현재 브랜치를 대상으로 할 수 없습니다 — 브랜치를 체크아웃하거나 대상을 지정하세요"
private const val ALREADY_IN_PROGRESS = "진행 중인 병합·리베이스·cherry-pick 이 있어 시작하지 않았습니다"
private const val HEAD_MISSING = "HEAD 를 읽을 수 없습니다"

/**
 * 시작을 막아야 하는 상태. **먼저 확인해야 하는 이유**는 되돌리기 때문이다 — 실패 복구가
 * `reset --hard` 를 쓰므로, 우리가 시작하지 않은 진행 중 작업 위에서 실패하면 그 작업을 지운다.
 */
private val IN_PROGRESS_STATES = setOf(
    RepositoryState.MERGING,
    RepositoryState.REBASING,
    RepositoryState.REVERTING,
    RepositoryState.CHERRY_PICKING,
)

/**
 * 대상 브랜치 확인 → 체크아웃 → 조작 실행을 **한 번에** 수행한다.
 *
 * 호출부가 이미 `GitAccess` 의 임계 구역을 열어 두었으므로 여기서 락을 다시 잡지 않는다 (결정 G4).
 * 조작 로직은 각 Gateway 구현의 내부 경로를 그대로 부른다 — 여기에 복제하면 두 경로가 갈라진다.
 */
internal fun Git.runOnBranchHeld(on: BranchTarget, operation: BranchOperation): BranchOperationResult {
    val branch = resolveBranchTarget(on)
    val fullRef = validatedBranchRef(branch)
    val branchTargetBefore = repository.requireRefTarget(fullRef, branch)
    val originalHead = repository.fullBranch ?: throw UndineException.StateViolation(HEAD_MISSING)
    if (repository.toOpenedRepository().state in IN_PROGRESS_STATES) {
        throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
    }
    // 체크아웃이 실패하면 아직 아무것도 바뀌지 않았다 — 되돌릴 것이 없으므로 복구 구간 밖에 둔다.
    if (repository.fullBranch != fullRef) {
        // 더티 거부는 checkoutHeld 안에 있다.
        checkoutHeld(branch, force = false)
    } else {
        // 대상이 이미 체크아웃돼 있으면 그 가드를 지나친다 — 같은 검사를 여기서 한다.
        // 커밋하지 않은 편집을 남긴 채 시작하면 아래 실패 복구의 reset --hard 가 그 편집을 지운다.
        rejectIfDirty()
    }

    return runCatching { executeHeld(operation, branch) }
        .onFailure { restoreHeld(originalHead, branchTargetBefore) }
        .getOrThrow()
}

/**
 * 브랜치를 [expected] 일 때만 [to] 로 옮기고, **실행 시점의 실제 HEAD** 가 그 브랜치면
 * 워킹트리·인덱스까지 맞춘다. ref 갱신을 먼저 해 두면 HEAD 는 심볼릭 참조를 따라 함께 움직이므로,
 * 뒤따르는 `reset --hard` 는 워킹트리를 새 HEAD 로 동기화하는 일만 한다.
 */
internal fun Git.hardResetBranchHeld(branch: RefName, to: CommitId, expected: CommitId) {
    val fullRef = validatedBranchRef(branch)
    val current = repository.requireRefTarget(fullRef, branch)
    requireExpectedTarget(branch, CommitId.of(current.name), expected)
    val onTargetBranch = repository.fullBranch == fullRef
    repository.updateRefHeld(fullRef, branch, requireCommitObject(to), current)
    if (onTargetBranch) {
        reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
    }
}

private fun Git.resolveBranchTarget(on: BranchTarget): RefName = when (on) {
    is BranchTarget.Named -> on.branch
    BranchTarget.Current -> repository.currentBranchName()
        ?: throw UndineException.StateViolation(DETACHED_HEAD_TARGET)
}

/** 체크아웃된 로컬 브랜치 이름. detached HEAD 나 커밋이 없는 저장소면 null 이다. */
private fun Repository.currentBranchName(): RefName? =
    fullBranch
        ?.takeIf { it.startsWith(LOCAL_BRANCH_PREFIX) }
        ?.let { RefName(Repository.shortenRefName(it)) }

private fun Git.executeHeld(operation: BranchOperation, performedOn: RefName): BranchOperationResult =
    when (operation) {
        is BranchOperation.Merge ->
            mergeHeld(operation.source, operation.allowFastForward).toResult(performedOn)

        is BranchOperation.Rebase -> rebaseHeld(operation.upstream).toResult(performedOn)

        is BranchOperation.CherryPick ->
            applyHeld(operation.commit, operation.recordOrigin).toResult(performedOn)
    }

/**
 * 실패한 조작의 흔적을 지운다 — 대상 브랜치를 조작 전 커밋으로 되돌려 워킹트리·인덱스와 진행 중
 * 상태(`MERGE_HEAD` 등)를 함께 정리하고, 호출 전 HEAD 로 다시 체크아웃한다.
 *
 * 시작 시점에 워킹트리가 깨끗하고 진행 중 작업도 없음을 [runOnBranchHeld] 가 보장하므로, 여기서
 * 지우는 것은 **이 호출이 만든 것뿐**이다 (결정 A-L1). 그래서 [mutatedHeld] 가 거짓이면 —
 * 없는 ref·없는 커밋처럼 조작이 시작되기도 전에 걸러진 검증 실패면 — 파괴적인 reset 을 하지 않고
 * 체크아웃만 되돌린다.
 */
private fun Git.restoreHeld(originalHead: String, branchTargetBefore: ObjectId) {
    if (mutatedHeld(branchTargetBefore)) {
        reset().setMode(ResetCommand.ResetType.HARD).setRef(branchTargetBefore.name).call()
    }
    if (repository.fullBranch != originalHead) {
        checkout().setName(originalHead).setForced(true).call()
    }
}

/** 조작이 실제로 무언가 바꿨는지 — 커밋 이동·진행 중 상태·워킹트리 변경 중 하나라도 있으면 참이다. */
private fun Git.mutatedHeld(branchTargetBefore: ObjectId): Boolean =
    repository.resolve(Constants.HEAD) != branchTargetBefore ||
        repository.toOpenedRepository().state in IN_PROGRESS_STATES ||
        status().call().uncommittedChanges.isNotEmpty()

private fun MergeResult.toResult(performedOn: RefName): BranchOperationResult = when (this) {
    is MergeResult.Succeeded -> BranchOperationResult.Succeeded(performedOn, head)
    is MergeResult.Conflicted -> BranchOperationResult.Conflicted(performedOn, paths)
    MergeResult.AlreadyUpToDate -> BranchOperationResult.NoChange(performedOn)
}

private fun RebaseResult.toResult(performedOn: RefName): BranchOperationResult = when (this) {
    is RebaseResult.Succeeded -> BranchOperationResult.Succeeded(performedOn, head)
    is RebaseResult.Conflicted -> BranchOperationResult.Conflicted(performedOn, paths)
    RebaseResult.AlreadyUpToDate -> BranchOperationResult.NoChange(performedOn)
}

private fun CherryPickStep.toResult(performedOn: RefName): BranchOperationResult = when (this) {
    is CherryPickStep.Created -> BranchOperationResult.Succeeded(performedOn, commit)
    is CherryPickStep.Conflicted -> BranchOperationResult.Conflicted(performedOn, paths)
    CherryPickStep.Empty -> BranchOperationResult.NoChange(performedOn)
}
