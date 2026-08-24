package dev.undine.infrastructure.git.merge

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.toOpenedRepository
import dev.undine.infrastructure.git.repository.toWorkingTreeStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import org.eclipse.jgit.api.RebaseResult as JGitRebaseResult

private const val OPERATION_STATE = "merge.repositoryState"
private const val OPERATION_MERGE = "merge.merge"
private const val OPERATION_CONTINUE_MERGE = "merge.continueMerge"
private const val OPERATION_ABORT_MERGE = "merge.abortMerge"
private const val OPERATION_REBASE = "merge.rebase"
private const val OPERATION_CONTINUE_REBASE = "merge.continueRebase"
private const val OPERATION_REBASING_COMMIT = "merge.rebasingCommit"
private const val OPERATION_SKIP_REBASE_COMMIT = "merge.skipRebaseCommit"
private const val OPERATION_ABORT_REBASE = "merge.abortRebase"

private const val START_POINT_MISSING = "되돌릴 시작 지점(ORIG_HEAD)이 없습니다"
/**
 * 진행 중인데 새로 시작하면 `rememberStartPoint` 가 `ORIG_HEAD` 를 **부분 진행 HEAD** 로 덮어써
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
private const val MERGE_NOT_IN_PROGRESS = "병합이 진행 중이 아닙니다"
private const val REBASE_NOT_IN_PROGRESS = "리베이스가 진행 중이 아닙니다"
private const val UNCONFIRMED_DISCARD = "확인한 뒤에 생긴 편집이 있어 중단하지 않았습니다"
private const val SKIP_TARGET_UNKNOWN = "건너뛸 커밋을 읽을 수 없어 건너뛰지 않았습니다"
private const val STALE_SKIP_CONFIRMATION = "확인한 커밋과 지금 건너뛸 커밋이 달라 건너뛰지 않았습니다"

/**
 * JGit 이 리베이스 상태를 쓰는 디렉토리와, 충돌로 멈춘 커밋을 남기는 파일. git 본체와 같은 이름이라
 * 외부 git 으로 시작한 리베이스도 같은 경로에서 읽힌다.
 */
private const val REBASE_MERGE_DIR = "rebase-merge"
private const val STOPPED_SHA_FILE = "stopped-sha"

/**
 * [MergeGateway] 의 JGit 구현.
 *
 * 공유 `Repository` 는 [GitAccess] 를 통해서만 만진다 — JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 스레드 전환을 그 경계가 책임진다. 이 구현은 자기 잠금이나 스레드 전환을
 * 다시 두지 않고, 핸들 수명도 소유하지 않는다(닫지 않는다).
 * 직접 여는 JGit 자원([Git])만 `use {}` 로 닫는다.
 *
 * **충돌은 실패가 아니다.** 병합·리베이스 충돌은 예외가 아니라 [MergeResult.Conflicted] ·
 * [RebaseResult.Conflicted] 로 돌려주고 저장소를 진행 중 상태로 남긴다. 반대로 사용자가 고칠 수 있는
 * 실패(대상 없음·워킹트리 더티)는 예상 못 한 실패([UndineException.GitOperationFailed])로 뭉뚱그리지 않는다.
 *
 * **되돌릴 수 없는 연산이 있다.** 리베이스는 커밋을 새로 쓰므로 [abortRebase] 없이 끝낸 뒤에는 원래
 * 커밋으로 돌아갈 수 없고, [skipRebaseCommit] 이 건너뛴 커밋은 결과 이력에서 사라진다.
 * Undo 스택(UND-38)에는 아직 기록하지 않는다 — 그 계약은 이 티켓 범위 밖이다.
 */
class MergeGatewayImpl(private val gitAccess: GitAccess) : MergeGateway {

    /**
     * 진행 중 상태를 **디스크에서** 읽는다 — 앱을 다시 켠 사용자도 진행 중 병합·리베이스를 만나
     * 빠져나올 수 있어야 한다. 매핑은 저장소 열기 경로와 같은 것([toOpenedRepository])을 재사용한다.
     */
    override suspend fun repositoryState(): RepositoryState =
        gitOperation(OPERATION_STATE) { git -> git.repository.toOpenedRepository().state }

    override suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult =
        gitOperation(OPERATION_MERGE) { git ->
            val targetRef = git.repository.requireRef(target)
            // 진행 중이면 시작하지 않는다 — 아래 rememberStartPoint 가 ORIG_HEAD 를 덮어쓰기 전에 막는다.
            if (git.repository.toOpenedRepository().state in IN_PROGRESS_STATES) {
                throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
            }
            git.repository.rememberStartPoint()
            git.merge()
                .include(targetRef)
                .setFastForward(fastForwardModeOf(allowFastForward))
                .call()
                .toDomain(git, OPERATION_MERGE)
        }

    /**
     * 인덱스에 올라간 해결 결과로 병합 커밋을 만든다. 미해결 파일이 남아 있으면 커밋하지 않고
     * [MergeResult.Conflicted] 를 그대로 유지한다 — 반쯤 해결된 상태를 커밋해 버리면 되돌리기 어렵다.
     */
    override suspend fun continueMerge(): MergeResult =
        gitOperation(OPERATION_CONTINUE_MERGE) { git ->
            // 병합 중이 아닌데 이어가면 staged 변경이 **일반 커밋**으로 나간다 — 사용자가 시키지 않은 커밋이다.
            git.repository.requireState(RepositoryState.MERGING, MERGE_NOT_IN_PROGRESS)
            val unresolved = git.unresolvedPaths()
            when {
                unresolved.isNotEmpty() -> MergeResult.Conflicted(unresolved)
                else -> MergeResult.Succeeded(
                    head = git.commitMerge(),
                    fastForward = false,
                )
            }
        }

    /**
     * `ORIG_HEAD` 에 남긴 시작 지점으로 워킹트리까지 되돌리고 `MERGE_HEAD` 를 지운다.
     * **충돌 해결 중이던 편집은 사라진다** — 되돌리는 것이 목적이므로 의도된 동작이다.
     */
    override suspend fun abortMerge(confirmation: AbortConfirmation) {
        gitOperation(OPERATION_ABORT_MERGE) { git ->
            // 같은 임계구역 안에서 다시 대조한다 — 서비스가 확인한 뒤 여기까지 오는 사이에도 편집이 생긴다.
            // 상태부터 본다. 진행 중이 아닌데 ORIG_HEAD 로 hard reset 하면 **끝난 작업을 되돌린다**.
            git.repository.requireState(RepositoryState.MERGING, MERGE_NOT_IN_PROGRESS)
            git.repository.requireConfirmedDiscard(confirmation)
            val startPoint = git.repository.readOrigHead()
                ?: throw UndineException.StateViolation(START_POINT_MISSING)
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(startPoint.name)
                .call()
        }
    }

    override suspend fun rebase(target: RefName): RebaseResult =
        gitOperation(OPERATION_REBASE) { git ->
            val targetRef = git.repository.requireRef(target)
            // 진행 중이면 시작하지 않는다 — 아래 rememberStartPoint 가 ORIG_HEAD 를 덮어쓰기 전에 막는다.
            if (git.repository.toOpenedRepository().state in IN_PROGRESS_STATES) {
                throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
            }
            git.repository.rememberStartPoint()
            git.rebase()
                .setUpstream(requireCommitOf(targetRef, target))
                .setUpstreamName(targetRef.name)
                .call()
                .toDomain(git, OPERATION_REBASE)
        }

    override suspend fun continueRebase(): RebaseResult =
        gitOperation(OPERATION_CONTINUE_REBASE) { git ->
            git.repository.requireState(RepositoryState.REBASING, REBASE_NOT_IN_PROGRESS)
            val unresolved = git.unresolvedPaths()
            when {
                unresolved.isNotEmpty() -> RebaseResult.Conflicted(unresolved)
                else -> git.rebase()
                    .setOperation(RebaseCommand.Operation.CONTINUE)
                    .call()
                    .toDomain(git, OPERATION_CONTINUE_REBASE)
            }
        }

    /**
     * 리베이스가 멈춰 있는 커밋을 **디스크에서** 읽는다 — 진행 중 상태와 같은 이유로, 앱을 다시 켠
     * 사용자도 건너뛰기 확인을 대조할 수 있어야 한다.
     *
     * 상태 파일이 없거나(진행 중이 아님) 내용이 커밋 해시가 아니면 null 이다. 여기서 예외를 던지지
     * 않는 것은 "읽을 수 없다" 를 정상 결과로 두고, 그때 건너뛸지 말지는 [MergeGateway] 계약대로
     * `MergeService` 가 판단하게 하기 위해서다.
     */
    override suspend fun rebasingCommit(): CommitId? =
        gitOperation(OPERATION_REBASING_COMMIT) { git ->
            git.repository.readStoppedSha()
        }

    /** **건너뛴 커밋의 변경은 결과 이력에서 사라지고 되돌릴 수 없다.** 대조와 실행이 한 임계구역이다. */
    override suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult =
        gitOperation(OPERATION_SKIP_REBASE_COMMIT) { git ->
            // 읽기와 SKIP 이 한 임계구역 안이라, 대조한 그 커밋이 그대로 건너뛰어진다.
            git.repository.requireState(RepositoryState.REBASING, REBASE_NOT_IN_PROGRESS)
            git.repository.requireConfirmedSkipTarget(confirmation)
            git.rebase()
                .setOperation(RebaseCommand.Operation.SKIP)
                .call()
                .toDomain(git, OPERATION_SKIP_REBASE_COMMIT)
        }

    /**
     * 진행 중인 리베이스를 버린다. 복구 기준은 병합 중단과 같은 `ORIG_HEAD` 다 — 시작 전 지점의 정본을
     * 한 곳으로 두면 두 경로가 어긋날 수 없다. JGit `Operation.ABORT` 는 리베이스 상태 디렉토리까지
     * 정리해야 하므로 그대로 쓰고, 그 결과가 `ORIG_HEAD` 와 어긋날 때만 그쪽으로 맞춘다.
     *
     * **충돌 해결 중이던 편집은 사라진다** — 되돌리는 것이 목적이므로 의도된 동작이다.
     */
    override suspend fun abortRebase(confirmation: AbortConfirmation) {
        gitOperation(OPERATION_ABORT_REBASE) { git ->
            git.repository.requireState(RepositoryState.REBASING, REBASE_NOT_IN_PROGRESS)
            git.repository.requireConfirmedDiscard(confirmation)
            val startPoint = git.repository.readOrigHead()
                ?: throw UndineException.StateViolation(START_POINT_MISSING)
            val status = git.rebase().setOperation(RebaseCommand.Operation.ABORT).call().status
            if (status != JGitRebaseResult.Status.ABORTED) {
                throw UndineException.GitOperationFailed("$OPERATION_ABORT_REBASE(${status.name})")
            }
            git.resetHardIfNotAt(startPoint)
        }
    }

    /**
     * 공유 핸들 경계([GitAccess.withRepository]) 안에서 [block] 을 돌리고, 예상하지 못한 JGit 실패만
     * [UndineException.GitOperationFailed] 로 번역한다. 도메인 예외는 그대로 통과한다.
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

/**
 * `ORIG_HEAD` 가 가리키는 시작 지점과 어긋났을 때만 워킹트리까지 그 지점으로 맞춘다.
 * 이미 그 지점이면 아무것도 하지 않는다 — 되돌릴 것이 없는데 `reset --hard` 를 한 번 더 돌릴 이유가 없다.
 */
private fun Git.resetHardIfNotAt(startPoint: ObjectId) {
    if (repository.resolve(Constants.HEAD) == startPoint) return
    reset().setMode(ResetCommand.ResetType.HARD).setRef(startPoint.name).call()
}

/**
 * 해결된 병합을 커밋한다. 메시지는 병합이 적어 둔 `MERGE_MSG` 를 쓴다 — git 과 같은 동작이고,
 * 그래야 이력에서 어떤 병합이었는지 읽을 수 있다.
 */
private fun Git.commitMerge(): CommitId =
    CommitId.of(commit().setMessage(repository.readMergeCommitMsg()).call().name)

/**
 * `rebase-merge/stopped-sha` 를 읽는다. 짧은 해시가 적혀 있을 수 있어 저장소에서 완전한 커밋으로
 * 풀어 [CommitId] 규격(40자)에 맞춘다. 풀 수 없으면 대조할 대상이 없는 것이므로 null 이다.
 */
private fun Repository.readStoppedSha(): CommitId? =
    File(directory, "$REBASE_MERGE_DIR/$STOPPED_SHA_FILE")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { stoppedSha -> runCatching { resolve(stoppedSha) }.getOrNull() }
        ?.let { resolved -> CommitId.of(resolved.name) }

/**
 * 확인 목록이 **지금** 사라질 편집을 다 담고 있는지 실행 직전에 다시 본다.
 * `MergeService` 의 같은 검사는 빠른 거절이고, 이것이 마지막 방어선이다 — 검사와 `reset --hard`
 * 사이에 편집이 생기면 사용자가 모르는 파일이 사라진다.
 */
private fun Repository.requireConfirmedDiscard(confirmation: AbortConfirmation) {
    val confirmed = confirmation.discardedPaths.toSet()
    val status = toWorkingTreeStatus()
    // 추적되지 않는 파일은 `reset --hard` 가 건드리지 않으므로 확인 대상이 아니다 (MergeService 와 같은 기준).
    val discarded = (status.staged.map { it.path } + status.unstaged.map { it.path } + status.conflicted)
        .distinct()
        .sorted()
    val unconfirmed = discarded.filterNot { it in confirmed }
    if (unconfirmed.isNotEmpty()) {
        throw UndineException.StateViolation("$UNCONFIRMED_DISCARD: ${unconfirmed.joinToString()}")
    }
}

/** 확인한 커밋이 **지금** 멈춰 있는 커밋과 같은지 실행 직전에 다시 본다. */
private fun Repository.requireConfirmedSkipTarget(confirmation: SkipConfirmation) {
    val skipping = readStoppedSha() ?: throw UndineException.StateViolation(SKIP_TARGET_UNKNOWN)
    if (skipping != confirmation.skippedCommit) {
        throw UndineException.StateViolation(
            "$STALE_SKIP_CONFIRMATION: 확인=${confirmation.skippedCommit}, 대상=$skipping",
        )
    }
}

/**
 * 진행 중 상태를 **실행과 같은 임계구역에서** 확인한다.
 *
 * `MergeService` 도 같은 검사를 하지만 그것은 다른 임계구역이라, 그 사이 작업이 끝나면 이 호출은
 * 진행 중이 아닌 저장소를 되돌리게 된다. Gateway 를 직접 부르는 경로도 이 검사를 지난다.
 */
private fun Repository.requireState(expected: RepositoryState, detail: String) {
    if (toOpenedRepository().state != expected) throw UndineException.StateViolation(detail)
}

