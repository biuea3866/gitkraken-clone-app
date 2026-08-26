package dev.undine.application.undo

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.RepositoryBaseline
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val HEAD = commitId(2)
private val PARENT = commitId(1)
private val RECORDED_AT = Instant.parse("2026-08-25T01:02:03Z")
private val FIXED_CLOCK: Clock = Clock.fixed(RECORDED_AT, ZoneOffset.UTC)

private val STASH = StashEntry(
    index = 0,
    message = "작업 중",
    target = commitId(3),
    createdAt = Instant.parse("2026-01-02T03:04:05Z"),
    includedUntracked = false,
)

private fun branch(name: RefName, target: CommitId, isCurrent: Boolean) = Branch(
    name = name,
    target = target,
    isCurrent = isCurrent,
    isRemote = false,
    upstream = null,
    ahead = 0,
    behind = 0,
)

private fun refGatewayOn(vararg branches: Branch): RefGateway = mockk<RefGateway>().also {
    coEvery { it.listBranches() } returns branches.toList()
}

/**
 * 기록은 **연산·되돌리기 방법·기록 시점의 기준 상태** 셋을 함께 남긴다.
 * 기준 상태를 호출부가 넘기지 않고 여기서 읽는 이유는, 호출부가 그것을 잊으면
 * 되돌리기가 외부 변경을 감지할 수 없기 때문이다.
 */
class OperationRecorderSpec : BehaviorSpec({

    Given("main 브랜치를 체크아웃한 저장소") {
        val refGateway = refGatewayOn(branch(MAIN, HEAD, isCurrent = true), branch(FEATURE, PARENT, false))

        When("커밋을 기록하면") {
            val stack = UndoStack()
            val recorded = OperationRecorder(refGateway, stack, FIXED_CLOCK).record(
                operation = GitOperationKind.COMMIT,
                strategy = UndoStrategy.SoftResetTo(PARENT),
            )

            Then("연산·되돌리기 방법·기록 시점의 브랜치와 HEAD 가 함께 쌓인다") {
                recorded shouldBe OperationEntry(
                    operation = GitOperationKind.COMMIT,
                    strategy = UndoStrategy.SoftResetTo(PARENT),
                    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
                    targetLabel = GitOperationKind.COMMIT.label,
                    recordedAt = RECORDED_AT,
                )
                stack.peek() shouldBe recorded
            }
        }

        When("복구 불가 연산을 기록하면") {
            val stack = UndoStack()
            val recorded = OperationRecorder(refGateway, stack, FIXED_CLOCK).recordIrreversible(
                operation = GitOperationKind.PUSH,
                reason = "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다",
                targetLabel = "origin/main",
            )

            Then("조용히 넘기지 않고 사유와 함께 스택에 남는다") {
                recorded.strategy shouldBe UndoStrategy.Irreversible("원격에 올라간 커밋은 앱이 되돌릴 수 없습니다")
                recorded.irreversibleReason shouldBe "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다"
                recorded.targetLabel shouldBe "origin/main"
                recorded.recordedAt shouldBe RECORDED_AT
                stack.history() shouldContainExactly listOf(recorded)
            }
        }

        When("여러 연산을 기록하면") {
            val stack = UndoStack()
            val recorder = OperationRecorder(refGateway, stack)
            val first = recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
            val second = recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(STASH))

            Then("최신 기록이 스택 최상단이다") {
                stack.history() shouldContainExactly listOf(second, first)
            }
        }
    }

    Given("detached HEAD 인 저장소") {
        val refGateway = refGatewayOn(branch(MAIN, PARENT, isCurrent = false))

        When("연산을 기록하면") {
            val stack = UndoStack()
            OperationRecorder(refGateway, stack).record(
                operation = GitOperationKind.CHERRY_PICK,
                strategy = UndoStrategy.HardResetTo(PARENT),
            )

            Then("브랜치 없음을 그대로 기록한다 — 되돌리기 단계에서 거부할 근거가 된다") {
                stack.peek()?.baseline shouldBe RepositoryBaseline(branch = null, head = null)
            }
        }
    }

    Given("커밋이 하나도 없는 저장소") {
        val refGateway = refGatewayOn()

        When("연산을 기록하면") {
            val stack = UndoStack()
            OperationRecorder(refGateway, stack).record(
                operation = GitOperationKind.STASH_PUSH,
                strategy = UndoStrategy.PopStash(STASH),
            )

            Then("기준 상태는 브랜치도 HEAD 도 없는 상태다") {
                stack.peek()?.baseline shouldBe RepositoryBaseline(branch = null, head = null)
            }
        }
    }
})
