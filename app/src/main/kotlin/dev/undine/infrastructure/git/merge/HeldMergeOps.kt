package dev.undine.infrastructure.git.merge

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.infrastructure.git.repository.toOpenedRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository

/**
 * 진행 중인데 새로 시작하면 [rememberStartPoint] 가 `ORIG_HEAD` 를 **부분 진행 HEAD** 로 덮어써
 * 그 뒤의 abort 가 되돌릴 지점을 잃는다 — 복구 불가다. `MergeService` 는 시작 전에 워킹트리 더티만
 * 보고 진행 중 상태는 보지 않는다(충돌 해결 중 워킹트리는 항상 더티라 그 검사가 통과한다).
 */
private const val ALREADY_IN_PROGRESS = "진행 중인 병합·리베이스가 있어 새로 시작하지 않았습니다"

/**
 * 시작을 막아야 하는 상태. **DETACHED·EMPTY 는 여기 없다** — detached HEAD 에서 병합을 시작하는 것은
 * git 이 허용하는 정상 동작이고, 빈 저장소는 대상 참조를 못 찾아 다른 사유로 실패한다.
 * 막아야 하는 것은 "이미 무언가 진행 중" 뿐이다.
 */
private val IN_PROGRESS_STATES = setOf(
    RepositoryState.MERGING,
    RepositoryState.REBASING,
    RepositoryState.REVERTING,
)

/*
 * 아래 두 함수는 [MergeGatewayImpl] 의 병합·리베이스 본체이자, 이미 `GitAccess` 의 임계 구역 안에서
 * 락을 쥔 핸들로 호출하는 내부 경로다 (결정 G4) — 체크아웃과 한 구역에서 돌려야 하는
 * `WorktreeOpsGateway.runOnBranch` 가 쓴다. 락도 스레드 전환도 여기서 다시 하지 않는다.
 * Gateway 메서드와 같은 함수를 쓰므로 두 경로가 갈라지지 않는다.
 */

internal fun Git.mergeHeld(target: RefName, allowFastForward: Boolean): MergeResult {
    val targetRef = repository.requireRef(target)
    // 진행 중이면 시작하지 않는다 — 아래 rememberStartPoint 가 ORIG_HEAD 를 덮어쓰기 전에 막는다.
    if (repository.toOpenedRepository().state in IN_PROGRESS_STATES) {
        throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
    }
    repository.rememberStartPoint()
    return merge()
        .include(targetRef)
        .setFastForward(fastForwardModeOf(allowFastForward))
        .call()
        .toDomain(this, OPERATION_MERGE)
}

internal fun Git.rebaseHeld(target: RefName): RebaseResult {
    val targetRef = repository.requireRef(target)
    if (repository.toOpenedRepository().state in IN_PROGRESS_STATES) {
        throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
    }
    repository.rememberStartPoint()
    return rebase()
        .setUpstream(requireCommitOf(targetRef, target))
        .setUpstreamName(targetRef.name)
        .call()
        .toDomain(this, OPERATION_REBASE)
}

private fun fastForwardModeOf(allowFastForward: Boolean): MergeCommand.FastForwardMode =
    if (allowFastForward) MergeCommand.FastForwardMode.FF else MergeCommand.FastForwardMode.NO_FF

/**
 * 대상 참조를 찾는다. 오타나 이미 사라진 브랜치는 **사용자가 고칠 수 있는** 실패이므로
 * 예상 못 한 실패로 뭉뚱그리지 않고 [UndineException.NotFound] 로 알린다.
 */
private fun Repository.requireRef(target: RefName): Ref =
    findRef(target.value) ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, target.value)

/** 참조는 있는데 가리키는 커밋이 없는 경우를 참조 부재와 구분한다. */
private fun requireCommitOf(ref: Ref, target: RefName): ObjectId =
    ref.objectId ?: throw UndineException.NotFound(UndineException.NotFound.Kind.COMMIT, target.value)

/**
 * 시작 전 HEAD 를 `ORIG_HEAD` 에 남긴다 — git 도 병합·리베이스 시작 시 같은 참조를 갱신하고,
 * [MergeGatewayImpl.abortMerge] 가 이 지점으로 되돌린다.
 * 커밋이 하나도 없는 저장소는 되돌릴 지점 자체가 없어 남기지 않는다.
 */
private fun Repository.rememberStartPoint() {
    resolve(Constants.HEAD)?.let { writeOrigHead(it) }
}
