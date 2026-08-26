package dev.undine.application.undo

import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoPlan
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.domain.undo.planUndo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 세션 Undo 스택의 최상단 항목 하나를 안전하게 실행한다.
 *
 * **화면이 미리 본 항목을 인자로 받는다.** 인자 없이 "마지막 것" 을 되돌리면, 미리 보기와 실행
 * 사이에 앱의 다른 연산이 새 기록을 남겼을 때 사용자에게 보여준 것과 다른 항목을 되돌린다
 * (wave 8 결정 G4). 확인과 소비는 [UndoStack.popIf] 가 한 임계 구역에서 처리한다 (A-L3).
 *
 * 모든 거부 항목도 소비한다. 같은 이유로 매번 막혀 그 아래의 유효한 항목까지 도달하지 못하는 상태를
 * 피하기 위해서이며, 거부 결과 자체는 호출자에게 빠짐없이 반환한다. 사용자가 사유를 **보지도 못한 채**
 * 최상단이 사라지는 일이 없도록, 막힌 최상단을 지우는 것은 [discardBlocked] 로 분리해 두었고
 * 거기서는 **되돌릴 수 있게 될 수 없는 기록만** 지운다.
 */
class UndoService(
    private val undoStack: UndoStack,
    private val refGateway: RefGateway,
    private val repositoryGateway: RepositoryGateway,
    private val worktreeOpsGateway: WorktreeOpsGateway,
) {

    /**
     * [expected] 가 여전히 최상단일 때만 그 항목 하나를 되돌린다.
     *
     * @param expected [preview] 로 사용자에게 보여준 바로 그 기록.
     */
    suspend fun undo(expected: OperationEntry): UndoExecution =
        if (undoStack.peek() == expected) {
            consumeAndApply(expected, plan(expected))
        } else {
            UndoExecution.TargetChanged
        }

    /**
     * **기록 시점에 이미 복구 불가로 남긴** 최상단 기록을 사용자가 확인한 뒤 이력에서 지운다.
     *
     * 이 경로가 없으면 복구 불가 최상단이 스택을 영영 막는다 — 그 아래에 되돌릴 수 있는 기록이
     * 있어도 도달할 방법이 없다. 지우는 것은 세션 기록뿐이고 저장소는 건드리지 않는다.
     *
     * **저장소 상태 때문에 막힌 기록은 지우지 않는다.** detached HEAD·외부 변경·미커밋 변경은
     * 사용자가 해소하면 되돌릴 수 있게 되는 **일시적** 사유다. 그런 기록을 지우면 되돌릴 수 있게
     * 됐을 때 되돌릴 방법이 사라진다 — 세션 기록은 저장소에 남지 않으므로 되살릴 수 없다.
     * 그래서 판단 기준을 저장소 상태가 아니라 **기록 자체의 성질**([UndoStrategy.Irreversible])로
     * 둔다. 이 성질은 기록이 만들어질 때 정해져 이후 바뀌지 않으므로, 판단과 소비 사이에 조건이
     * 뒤집히는 창이 아예 없다 — 확인과 제거는 [UndoStack.popIf] 한 임계 구역에서 끝난다 (A-L3).
     *
     * 그리고 일시적 사유는 **스택을 막지도 않는다.** detached HEAD·외부 변경은 아래 기록에도
     * 똑같이 걸리고, 미커밋 변경은 커밋·stash 하면 풀린다. 최상단을 지워도 얻는 것이 없다.
     *
     * 지울 수 없는 기록에는 아무것도 하지 않고 [UndoExecution.TargetChanged] 로 돌려보내,
     * 화면이 다시 읽어 지금의 대상과 사유를 보여주게 한다.
     */
    suspend fun discardBlocked(expected: OperationEntry): UndoExecution {
        val refusal = expected.irreversibleRefusal() ?: return UndoExecution.TargetChanged

        return if (undoStack.popIf(expected)) {
            UndoExecution.Discarded(expected, refusal)
        } else {
            UndoExecution.TargetChanged
        }
    }

    /**
     * 실행하지 않고 최상단 항목의 되돌리기 가능 여부만 판단한다. **스택을 바꾸지 않는다.**
     *
     * 화면이 버튼을 누르기 전에 대상과 불가 사유를 말할 수 있어야 하므로 [undo] 와 같은 판단
     * ([plan])을 쓰되 [UndoStack.peek] 로만 읽는다 — 미리 보기가 항목을 소비하면 사용자는
     * 누르지도 않은 기록을 잃는다.
     *
     * 여기서 통과했다고 [undo] 가 반드시 성공하는 것은 아니다. 검사와 실행 사이의 **외부** 변경은
     * 방어 대상이 아니며(wave 8 결정 A-M1), 그 경우에도 [undo] 가 실행 직전 같은 판단을 다시 한다.
     */
    suspend fun preview(): UndoTarget {
        val entry = undoStack.peek() ?: return UndoTarget.None

        return when (val plan = plan(entry)) {
            is UndoPlan.Refuse -> UndoTarget.Blocked(entry, plan.outcome)
            is UndoPlan.Execute -> UndoTarget.Undoable(entry)
        }
    }

    /**
     * 판단에 **필요한 만큼만** 저장소를 조회한다.
     *
     * 복구 불가 항목은 조회조차 하지 않는다 — Git 을 전혀 건드리지 않고 사유만 돌려주기 위해서다.
     * 워킹트리 상태도 그것을 덮어쓰는 되돌리기일 때만 읽는다.
     */
    private suspend fun plan(entry: OperationEntry): UndoPlan {
        entry.irreversibleRefusal()?.let { return UndoPlan.Refuse(it) }

        val dirtyPaths = if (entry.strategy is UndoStrategy.HardResetTo) {
            repositoryGateway.status().dirtyPaths()
        } else {
            emptyList()
        }
        return entry.planUndo(refGateway.currentBaseline(), dirtyPaths)
    }

    /**
     * 소비와 실행을 **취소되지 않는 한 구간**에서 끝낸다.
     *
     * 이 두 단계는 화면 수명이 아니라 저장소 정합성에 묶여 있다. 리컴포지션·화면 이탈로 호출한
     * Compose scope 이 취소되면, 그 사이에 낀 취소가 기록만 소비된 채 저장소는 그대로인 상태를
     * 남긴다 — 사용자는 되돌릴 방법을 잃는다 (wave 8 결정 G4·A-L2).
     *
     * 실제 blocking I/O 는 각 Gateway 구현이 `Dispatchers.IO` 로 옮기므로 여기서 다시 옮기지 않는다.
     *
     * 소비 직전에 [UndoStack.popIf] 로 최상단을 **한 번 더 확인**한다. [plan] 을 위한 저장소 조회
     * 동안 앱의 다른 연산이 새 기록을 남겼을 수 있고, 그러면 판단한 것과 다른 항목을 소비한다.
     *
     * 실패는 여기서 삼키지 않는다 — 화면 상태로 바꾸는 것은 상태 홀더의 몫이다.
     */
    private suspend fun consumeAndApply(entry: OperationEntry, plan: UndoPlan): UndoExecution =
        withContext(NonCancellable) {
            if (!undoStack.popIf(entry)) {
                UndoExecution.TargetChanged
            } else {
                UndoExecution.Completed(
                    when (plan) {
                        is UndoPlan.Refuse -> plan.outcome
                        is UndoPlan.Execute -> execute(entry, plan.strategy)
                    },
                )
            }
        }

    private suspend fun execute(
        entry: OperationEntry,
        strategy: UndoStrategy.Reversible,
    ): UndoOutcome = when (strategy) {
        is UndoStrategy.SoftResetTo -> {
            worktreeOpsGateway.reset(strategy.commit, ResetMode.SOFT)
            UndoOutcome.Undone(entry.operation, strategy)
        }

        is UndoStrategy.CheckoutRef -> {
            refGateway.checkout(strategy.ref, force = false)
            UndoOutcome.Undone(entry.operation, strategy)
        }

        is UndoStrategy.DeleteBranch -> when (refGateway.deleteBranch(strategy.branch, force = false)) {
            DeleteBranchResult.DELETED -> UndoOutcome.Undone(entry.operation, strategy)
            DeleteBranchResult.REFUSED_UNMERGED -> UndoOutcome.UnmergedBranch(strategy.branch)
        }

        /*
         * 이동 되돌리기는 **되돌릴 때도 조건부 갱신**을 쓴다 (결정 G2). 기록 이후 다른 경로가 그
         * ref 를 옮겼다면 되돌리기가 그 이동을 덮어써 도달할 수 없는 커밋을 만든다. 어긋났을 때
         * Gateway 가 올리는 거부는 여기서 잡지 않는다 — 성공으로 숨기면 사용자는 되돌려진 줄 안다.
         */
        is UndoStrategy.MoveBranchTo -> {
            refGateway.moveBranch(strategy.branch, to = strategy.previous, expected = strategy.expected)
            UndoOutcome.Undone(entry.operation, strategy)
        }

        is UndoStrategy.MoveTagTo -> {
            refGateway.moveTag(strategy.tag, to = strategy.previous, expected = strategy.expected)
            UndoOutcome.Undone(entry.operation, strategy)
        }

        is UndoStrategy.HardResetTo -> {
            worktreeOpsGateway.hardResetBranch(
                strategy.branch,
                to = strategy.previous,
                expected = strategy.expected,
            )
            UndoOutcome.Undone(entry.operation, strategy)
        }

        is UndoStrategy.PopStash -> {
            // 기록한 stash 를 target 으로 지목해 적용하고 지운다. 최신 stash 를 pop 하면 기록 뒤
            // 밖에서 쌓인 다른 stash 를 풀고 지우게 된다. 대상이 사라졌으면 Gateway 가 적용 전에
            // UndineException.NotFound 로 멈춘다 — 저장소는 그대로다.
            worktreeOpsGateway.stashApply(strategy.stash)
            worktreeOpsGateway.stashDrop(strategy.stash)
            UndoOutcome.Undone(entry.operation, strategy)
        }
    }
}

/**
 * 이 기록이 **기록 시점부터 복구 불가**였다면 그 거부 사유, 아니면 null.
 *
 * 저장소를 전혀 읽지 않는다 — 판단 근거가 불변인 [OperationEntry.strategy] 하나뿐이라 언제 물어도
 * 같은 답이 나온다. 그래서 [UndoService.discardBlocked] 가 이력에서 지워도 되는지의 기준으로 쓴다.
 */
private fun OperationEntry.irreversibleRefusal(): UndoOutcome.Irreversible? =
    (strategy as? UndoStrategy.Irreversible)?.let { UndoOutcome.Irreversible(operation, it.reason) }
