package dev.undine.domain.undo

import dev.undine.domain.RefName
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val HEAD_AFTER = commitId(2)
private val HEAD_BEFORE = commitId(1)

private val RECORDED = RepositoryBaseline(branch = MAIN, head = HEAD_AFTER)

private fun entryOf(strategy: UndoStrategy, operation: GitOperationKind = GitOperationKind.COMMIT) =
    OperationEntry(operation = operation, strategy = strategy, baseline = RECORDED)

/**
 * 되돌리기 전 판단 규칙 — 복구 불가, detached HEAD, 외부 변경, 커밋되지 않은 변경.
 *
 * 어느 하나라도 조용히 통과하면 Git 이 엉뚱하게 바뀐다. 판단은 domain 이 하고
 * 실행은 application 이 한다.
 */
class UndoPlanSpec : FunSpec({

    test("기준 상태가 같고 브랜치 위면 기록된 전략을 그대로 실행한다") {
        val strategy = UndoStrategy.SoftResetTo(HEAD_BEFORE)

        entryOf(strategy).planUndo(RECORDED, dirtyPaths = emptyList()) shouldBe UndoPlan.Execute(strategy)
    }

    test("복구 불가 연산은 실행하지 않고 사람이 읽을 수 있는 사유를 돌려준다") {
        val entry = entryOf(
            strategy = UndoStrategy.Irreversible("원격에 올라간 커밋은 앱이 되돌릴 수 없습니다"),
            operation = GitOperationKind.PUSH,
        )

        val refused = entry.planUndo(RECORDED, dirtyPaths = emptyList())
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome
            .shouldBeInstanceOf<UndoOutcome.Irreversible>()

        refused.operation shouldBe GitOperationKind.PUSH
        refused.reason shouldContain "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다"
    }

    test("detached HEAD 에서는 사유를 붙여 거부한다") {
        val detached = RepositoryBaseline(branch = null, head = HEAD_AFTER)

        val refused = entryOf(UndoStrategy.SoftResetTo(HEAD_BEFORE))
            .planUndo(detached, dirtyPaths = emptyList())
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome

        refused.shouldBeInstanceOf<UndoOutcome.NoCurrentBranch>()
        refused.reason shouldContain "detached"
    }

    test("기록 이후 HEAD 가 움직였으면 외부 변경으로 거부한다") {
        val moved = RepositoryBaseline(branch = MAIN, head = commitId(9))

        val refused = entryOf(UndoStrategy.SoftResetTo(HEAD_BEFORE))
            .planUndo(moved, dirtyPaths = emptyList())
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome
            .shouldBeInstanceOf<UndoOutcome.ExternalChange>()

        refused.recorded shouldBe RECORDED
        refused.current shouldBe moved
    }

    test("기록 이후 다른 브랜치로 옮겼으면 외부 변경으로 거부한다") {
        val switched = RepositoryBaseline(branch = FEATURE, head = HEAD_AFTER)

        entryOf(UndoStrategy.SoftResetTo(HEAD_BEFORE))
            .planUndo(switched, dirtyPaths = emptyList())
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome
            .shouldBeInstanceOf<UndoOutcome.ExternalChange>()
    }

    test("ORIG_HEAD 복구는 워킹트리가 더러우면 거부한다 — undo 가 미커밋 작업을 삼키면 안 된다") {
        val refused = entryOf(UndoStrategy.HardResetTo(HEAD_BEFORE), GitOperationKind.MERGE)
            .planUndo(RECORDED, dirtyPaths = listOf("a.txt", "b.txt"))
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome
            .shouldBeInstanceOf<UndoOutcome.UncommittedChanges>()

        refused.paths shouldBe listOf("a.txt", "b.txt")
        refused.reason shouldContain "2"
    }

    test("워킹트리가 깨끗하면 ORIG_HEAD 로 hard reset 한다") {
        val strategy = UndoStrategy.HardResetTo(HEAD_BEFORE)

        entryOf(strategy, GitOperationKind.MERGE).planUndo(RECORDED, dirtyPaths = emptyList()) shouldBe
            UndoPlan.Execute(strategy)
    }

    test("워킹트리가 더러워도 soft reset 되돌리기는 막지 않는다 — 아무것도 잃지 않는다") {
        val strategy = UndoStrategy.SoftResetTo(HEAD_BEFORE)

        entryOf(strategy).planUndo(RECORDED, dirtyPaths = listOf("a.txt")) shouldBe UndoPlan.Execute(strategy)
    }

    test("복구 불가 판정이 detached·외부 변경보다 먼저다 — 사유가 사라지면 안 된다") {
        val entry = entryOf(UndoStrategy.Irreversible("stash 를 지우면 되살릴 수 없습니다"), GitOperationKind.STASH_DROP)
        val detachedAndMoved = RepositoryBaseline(branch = null, head = commitId(9))

        entry.planUndo(detachedAndMoved, dirtyPaths = listOf("a.txt"))
            .shouldBeInstanceOf<UndoPlan.Refuse>()
            .outcome
            .shouldBeInstanceOf<UndoOutcome.Irreversible>()
    }

    test("GitOperationKind 는 되돌릴 수 있는 다섯 연산과 복구 불가 세 연산으로 닫혀 있다") {
        GitOperationKind.entries.map { it.name } shouldBe listOf(
            "COMMIT",
            "CHECKOUT",
            "BRANCH_CREATE",
            "MERGE",
            "REBASE",
            "CHERRY_PICK",
            "STASH_PUSH",
            "PUSH",
            "HARD_RESET",
            "STASH_DROP",
        )
    }
})
