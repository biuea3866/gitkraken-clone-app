package dev.undine.application.graphops

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.BranchOperation
import dev.undine.domain.BranchOperationResult
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.logging.Level
import java.util.logging.Logger

private val LOGGER: Logger = Logger.getLogger("dev.undine.application.graphops.ExecuteGraphOperationUseCase")

/**
 * 그래프 조작 하나의 결과.
 *
 * 충돌을 실패로 접지 않는 것이 요점이다 — [Conflicted] 는 저장소가 **진행 중 상태**로 남았다는 뜻이고,
 * 화면은 그 사실과 해결 경로를 사용자에게 남겨야 한다.
 */
sealed interface GraphOperationOutcome {

    /** 이 조작이 다룬 참조 — 브랜치 조작은 실제 수행 브랜치, 태그 이동은 그 태그다. */
    val ref: RefName

    /**
     * 조작이 끝났다.
     *
     * [undoRecordFailure] 가 null 이 아니면 **저장소 변경은 성공했고 Undo 항목만 남지 않았다.**
     * 기록 실패를 조작 실패로 승격하면 사용자는 바뀐 저장소를 보고 "실패" 안내를 받고, 로그로만
     * 삼키면 되돌릴 수 없게 된 사실이 화면에 닿지 않는다 (`RecoveryOutcome` 과 같은 판단).
     */
    data class Completed(
        override val ref: RefName,
        val head: CommitId,
        val undoRecordFailure: UndineException?,
    ) : GraphOperationOutcome

    /** 충돌로 멈췄다. 실패가 아니다 — 사용자가 이어서 해결하거나 abort 한다. */
    data class Conflicted(override val ref: RefName, val paths: List<String>) : GraphOperationOutcome

    /** 적용할 변경이 없어 아무것도 바뀌지 않았다. */
    data class NoChange(override val ref: RefName) : GraphOperationOutcome
}

/**
 * 그래프에서 제안한 조작 하나를 실행하고 되돌리기를 기록한다.
 *
 * **UND-71 의 계약을 호출만 한다.** 체크아웃+조작의 원자성은 [WorktreeOpsGateway.runOnBranch] 가,
 * ref 이동의 조건부 갱신은 [RefGateway.moveTag]·[WorktreeOpsGateway.hardResetBranch] 가 소유한다 —
 * 여기서 잠금을 잡거나 조작 로직을 다시 구현하지 않는다 (결정 G4·A-N1).
 *
 * 기대 target 은 **조작 직전에 Gateway 에서 읽는다.** 화면 스냅샷을 그대로 넘기면 드래그를 시작한
 * 뒤 그 사이 옮겨진 ref 를 덮어써, 그 커밋으로만 도달하던 이력을 잃는다 (결정 G2).
 *
 * 변경과 기록은 [NonCancellable] 한 단위로 묶는다 — 조작이 끝난 뒤에 취소가 떨어져 기록을 건너뛰면
 * 저장소는 바뀌었는데 되돌릴 방법이 없다 (결정 A-L2·G4). 묶기 **전에** 호출자의 취소를 확인해,
 * 아직 아무것도 바꾸지 않은 시점의 취소는 존중한다.
 */
class ExecuteGraphOperationUseCase(
    private val worktreeOpsGateway: WorktreeOpsGateway,
    private val refGateway: RefGateway,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(operation: GraphOperation): GraphOperationOutcome {
        currentCoroutineContext().ensureActive()
        return when (operation) {
            is GraphOperation.Merge -> runOnBranch(
                on = operation.into,
                // git 기본 동작과 같게 빨리 감기를 허용한다 — 금지하면 명령행 결과와 달라진다 (결정 G7).
                branchOperation = BranchOperation.Merge(operation.source, allowFastForward = true),
                kind = GitOperationKind.MERGE,
                targetLabel = operation.source.value,
            )

            is GraphOperation.Rebase -> runOnBranch(
                on = operation.branch,
                branchOperation = BranchOperation.Rebase(operation.upstream),
                kind = GitOperationKind.REBASE,
                targetLabel = operation.upstream.value,
            )

            is GraphOperation.CherryPick -> runOnBranch(
                on = operation.onto,
                // `-x` 없이 적용한다 — git 기본값이고, 원본 표기 여부는 이 화면이 정할 일이 아니다.
                branchOperation = BranchOperation.CherryPick(operation.commit, recordOrigin = false),
                kind = GitOperationKind.CHERRY_PICK,
                targetLabel = operation.commit.value,
            )

            is GraphOperation.ResetBranch -> resetBranch(operation)

            is GraphOperation.MoveTag -> moveTag(operation)
        }
    }

    /**
     * 되돌릴 이전 위치는 **결과가 준 값**을 쓴다 (`previousTarget`, UND-72). 호출부에서 따로 읽으면
     * 그 읽기와 조작 사이에 앱 내부의 다른 조작이 끼어들어 실제 조작 전 위치와 다른 값으로 기록된다 —
     * 임계 구역은 gateway 가 소유하므로 여기서 그 창을 닫을 방법이 없다 (결정 A-N1).
     */
    private suspend fun runOnBranch(
        on: BranchTarget,
        branchOperation: BranchOperation,
        kind: GitOperationKind,
        targetLabel: String,
    ): GraphOperationOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 변경과 Undo 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            when (val result = worktreeOpsGateway.runOnBranch(on, branchOperation)) {
                is BranchOperationResult.Succeeded -> completed(
                    strategy = UndoStrategy.HardResetTo(
                        result.performedOn,
                        previous = result.previousTarget,
                        expected = result.head,
                    ),
                    baseline = result.baseline,
                    kind = kind,
                    targetLabel = targetLabel,
                )

                is BranchOperationResult.Conflicted ->
                    GraphOperationOutcome.Conflicted(result.performedOn, result.paths)

                is BranchOperationResult.NoChange -> GraphOperationOutcome.NoChange(result.performedOn)
            }
        }
    }

    private suspend fun resetBranch(operation: GraphOperation.ResetBranch): GraphOperationOutcome {
        val expected = branchTargets()[operation.branch]
            ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, operation.branch.value)

        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 변경과 Undo 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val baseline =
                worktreeOpsGateway.hardResetBranch(operation.branch, to = operation.to, expected = expected)
            completed(
                strategy = UndoStrategy.HardResetTo(
                    operation.branch,
                    previous = expected,
                    expected = operation.to,
                ),
                baseline = baseline,
                kind = GitOperationKind.BRANCH_MOVE,
                targetLabel = operation.branch.value,
            )
        }
    }

    private suspend fun moveTag(operation: GraphOperation.MoveTag): GraphOperationOutcome {
        val expected = refGateway.listTags().firstOrNull { it.name == operation.tag }?.target
            ?: throw UndineException.NotFound(UndineException.NotFound.Kind.REF, operation.tag.value)

        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 변경과 Undo 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val baseline = refGateway.moveTag(operation.tag, to = operation.to, expected = expected)
            val failure = recordQuietly(GitOperationKind.TAG_MOVE) {
                operationRecorder.record(
                    GitOperationKind.TAG_MOVE,
                    UndoStrategy.MoveTagTo(operation.tag, previous = expected, expected = operation.to),
                    baseline,
                    operation.tag.value,
                )
            }
            GraphOperationOutcome.Completed(operation.tag, operation.to, failure)
        }
    }

    /** 로컬 브랜치의 조작 직전 위치. 원격 추적 브랜치는 이 화면의 조작 대상이 아니다. */
    private suspend fun branchTargets(): Map<RefName, CommitId> =
        refGateway.listBranches().filterNot { it.isRemote }.associate { it.name to it.target }

    /**
     * 되돌리기는 **이전 위치와 기대 위치를 둘 다** 갖는다 (결정 G2·G5) — 이전 값만 저장하면 기록 뒤
     * 다른 경로가 그 브랜치를 옮겼을 때 되돌리기가 그 이동을 조용히 덮어쓴다.
     *
     * 기준 상태 [baseline] 도 같은 이유로 **결과가 준 값**을 쓴다 (UND-73). 기록 시점에 여기서
     * 다시 읽으면 그 읽기와 변경 사이에 앱 내부의 다른 조작이 끼어들어, 되돌리기 직전의 외부 변경
     * 비교가 오염된다 — 거부해야 할 되돌리기가 통과한다.
     */
    private suspend fun completed(
        strategy: UndoStrategy.HardResetTo,
        baseline: RepositoryBaseline,
        kind: GitOperationKind,
        targetLabel: String,
    ): GraphOperationOutcome.Completed {
        val failure = recordQuietly(kind) {
            operationRecorder.record(kind, strategy, baseline, targetLabel)
        }
        return GraphOperationOutcome.Completed(strategy.branch, strategy.expected, failure)
    }

    /**
     * 기록 실패를 저장소 변경 실패로 승격하지 않고 **사유를 호출자에게 돌려준다.**
     *
     * 여기 오는 시점에 Git 변경은 이미 적용돼 있다. 취소는 삼키지 않는다 —
     * [NonCancellable] 구간이라 여기서 올라오는 취소는 기록 자체의 실패가 아니다.
     */
    private suspend fun recordQuietly(
        operation: GitOperationKind,
        record: suspend () -> Unit,
    ): UndineException? =
        try {
            record()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            LOGGER.log(Level.WARNING, "undo record failed after applied change: operation=$operation", failure)
            failure
        }
}
