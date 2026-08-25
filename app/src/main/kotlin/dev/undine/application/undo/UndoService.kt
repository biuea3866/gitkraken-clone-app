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

/**
 * 세션 Undo 스택의 최상단 항목 하나를 안전하게 실행한다.
 *
 * 모든 거부 항목도 소비한다. 같은 이유로 매번 막혀 그 아래의 유효한 항목까지 도달하지 못하는 상태를
 * 피하기 위해서이며, 거부 결과 자체는 호출자에게 빠짐없이 반환한다.
 */
class UndoService(
    private val undoStack: UndoStack,
    private val refGateway: RefGateway,
    private val repositoryGateway: RepositoryGateway,
    private val worktreeOpsGateway: WorktreeOpsGateway,
) {

    suspend fun undo(): UndoOutcome {
        val entry = undoStack.pop() ?: return UndoOutcome.NothingToUndo

        return when (val plan = plan(entry)) {
            is UndoPlan.Refuse -> plan.outcome
            is UndoPlan.Execute -> execute(entry, plan.strategy)
        }
    }

    /**
     * 판단에 **필요한 만큼만** 저장소를 조회한다.
     *
     * 복구 불가 항목은 조회조차 하지 않는다 — Git 을 전혀 건드리지 않고 사유만 돌려주기 위해서다.
     * 워킹트리 상태도 그것을 덮어쓰는 되돌리기일 때만 읽는다.
     */
    private suspend fun plan(entry: OperationEntry): UndoPlan {
        val strategy = entry.strategy
        if (strategy is UndoStrategy.Irreversible) {
            return UndoPlan.Refuse(UndoOutcome.Irreversible(entry.operation, strategy.reason))
        }

        val dirtyPaths = if (strategy is UndoStrategy.HardResetTo) {
            repositoryGateway.status().dirtyPaths()
        } else {
            emptyList()
        }
        return entry.planUndo(refGateway.currentBaseline(), dirtyPaths)
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

        is UndoStrategy.HardResetTo -> {
            worktreeOpsGateway.hardReset(strategy.commit)
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
