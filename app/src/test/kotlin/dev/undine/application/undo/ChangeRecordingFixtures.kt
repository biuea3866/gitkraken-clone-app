package dev.undine.application.undo

import dev.undine.application.cherrypick.CherryPickCommitsUseCase
import dev.undine.application.cherrypick.ContinueCherryPickUseCase
import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.merge.MergeBranchUseCase
import dev.undine.application.merge.RebaseBranchUseCase
import dev.undine.application.rebase.ApplyRebasePlanUseCase
import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.staging.AmendCommitUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.domain.AmendPreflight
import dev.undine.domain.CheckoutResult
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.PushResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryState
import dev.undine.domain.StagingGateway
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.cherrypick.CherryPickGateway
import dev.undine.domain.cherrypick.CherryPickService
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebaseTarget
import dev.undine.domain.undo.UndoStack
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import dev.undine.testsupport.spyRecorderOf
import io.mockk.coEvery
import io.mockk.mockk

/*
 * UND-79 기록 경로 공용 대역.
 *
 * Gateway 는 domain interface 의 대역이다 — JGit 이 실제로 이 값들을 같은 임계 구역에서 캡처하는지는
 * `*ImplSpec` 과 시나리오가 임시 저장소로 본다 (testing 규칙 1·3). 여기서 보는 것은
 * **UseCase 가 결과가 준 값을 그대로 기록에 넘기는가** 다.
 */

internal val RECORDED_BRANCH: RefName = RefName("main")
internal val RECORDED_TARGET: RefName = RefName("feature")

/** 변경 직전 지점. 되돌리기의 목적지다. */
internal val BEFORE_CHANGE: CommitId = commitId(1)

/** 변경이 만든 위치. 되돌리기의 기대 위치(expected)다. */
internal val AFTER_CHANGE: CommitId = commitId(2)

/** 변경 Gateway 가 자기 임계 구역에서 캡처해 결과에 실어 준 기준 상태 (UND-73). */
internal val BASELINE_AFTER: RepositoryBaseline = baselineOf(AFTER_CHANGE)

internal val CLEAN_STATUS: WorkingTreeStatus =
    WorkingTreeStatus(emptyList(), emptyList(), emptyList(), emptyList())

internal fun cleanRepositoryGateway(): RepositoryGateway = mockk<RepositoryGateway>().also {
    coEvery { it.status() } returns CLEAN_STATUS
}

internal fun stagingGatewayCommitting(
    result: CommitResult = CommitResult(AFTER_CHANGE, previousHead = BEFORE_CHANGE, baseline = BASELINE_AFTER),
    existsOnRemote: Boolean = false,
): StagingGateway = mockk<StagingGateway>().also {
    coEvery { it.commit(any()) } returns result
    coEvery { it.amend(any(), any()) } returns result
    coEvery { it.inspectAmend() } returns AmendPreflight(BEFORE_CHANGE, existsOnRemote = existsOnRemote)
}

internal fun refGatewayCheckingOut(
    result: CheckoutResult = CheckoutResult(previousRef = RECORDED_BRANCH, baseline = BASELINE_AFTER),
): RefGateway = mockk<RefGateway>().also {
    coEvery { it.checkout(any(), any()) } returns result
}

internal fun remoteGatewayPushing(result: PushResult): RemoteGateway =
    mockk<RemoteGateway>().also {
        coEvery { it.push(any(), any(), any()) } returns result
    }

internal fun mergeGatewayReturning(
    mergeResult: MergeResult = MergeResult.Succeeded(AFTER_CHANGE, fastForward = false, BEFORE_CHANGE, BASELINE_AFTER),
    rebaseResult: RebaseResult = RebaseResult.Succeeded(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER),
    state: RepositoryState = RepositoryState.NORMAL,
): MergeGateway = mockk<MergeGateway>().also {
    coEvery { it.repositoryState() } returns state
    coEvery { it.merge(any(), any()) } returns mergeResult
    coEvery { it.continueMerge() } returns mergeResult
    coEvery { it.rebase(any()) } returns rebaseResult
    coEvery { it.continueRebase() } returns rebaseResult
}

internal fun cherryPickGatewayApplying(
    step: CherryPickStep = CherryPickStep.Created(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER),
): CherryPickGateway = mockk<CherryPickGateway>().also {
    coEvery { it.repositoryState() } returns RepositoryState.NORMAL
    coEvery { it.orderOldestFirst(any()) } coAnswers { firstArg() }
    coEvery { it.apply(any(), any()) } returns step
}

internal fun cherryPickGatewayContinuing(
    step: CherryPickStep = CherryPickStep.Created(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER),
): CherryPickGateway = mockk<CherryPickGateway>().also {
    coEvery { it.repositoryState() } returns RepositoryState.CHERRY_PICKING
    coEvery { it.stoppedAt() } returns AFTER_CHANGE
    coEvery { it.continueAfterResolve() } returns step
}

internal fun interactiveRebaseGatewayApplying(
    outcome: InteractiveRebaseOutcome = InteractiveRebaseOutcome.Completed(BEFORE_CHANGE, BASELINE_AFTER),
): InteractiveRebaseGateway = mockk<InteractiveRebaseGateway>().also {
    coEvery { it.listTargets(any()) } returns listOf(RebaseTarget(commit(3), isPushed = false))
    coEvery { it.apply(any(), any()) } returns outcome
}

/** 한 줄짜리 계획. 계획 규칙 자체는 `RebasePlan` 스펙이 본다. */
internal fun singleStepPlan(): RebasePlan = RebasePlan.of(listOf(RebaseTarget(commit(3), isPushed = false)))

/** 기록 경로를 통째로 조립한 대역 묶음. 각 UseCase 가 같은 [stack] 에 남긴다. */
@Suppress("LongParameterList") // 여섯 기록 경로를 한 이력에 모으는 자리다 — 묶으면 각 스펙이 더 길어진다.
internal class RecordingHarness(
    val stack: UndoStack = UndoStack(),
    val staging: StagingGateway = stagingGatewayCommitting(),
    val refs: RefGateway = refGatewayCheckingOut(),
    val merge: MergeGateway = mergeGatewayReturning(),
    val cherryPick: CherryPickGateway = cherryPickGatewayApplying(),
    val interactiveRebase: InteractiveRebaseGateway = interactiveRebaseGatewayApplying(),
    val remote: RemoteGateway = remoteGatewayPushing(PushResult.Accepted),
) {
    val recorder = spyRecorderOf(stack)

    private val mergeService = MergeService(cleanRepositoryGateway(), merge)
    private val cherryPickService = CherryPickService(cleanRepositoryGateway(), cherryPick)

    val commitStaged = CommitStagedUseCase(staging, recorder)
    val amendCommit = AmendCommitUseCase(staging, recorder)
    val checkoutBranch = CheckoutBranchUseCase(refs, recorder)
    val pushRemote = PushRemoteUseCase(remote, recorder)
    val mergeBranch = MergeBranchUseCase(mergeService, recorder)
    val rebaseBranch = RebaseBranchUseCase(mergeService, recorder)
    val continueAfterResolve = ContinueAfterResolveUseCase(mergeService, recorder)
    val cherryPickCommits = CherryPickCommitsUseCase(cherryPickService, recorder)
    val continueCherryPick = ContinueCherryPickUseCase(cherryPickService, recorder)
    val applyRebasePlan = ApplyRebasePlanUseCase(interactiveRebase, recorder)
}
