package dev.undine.application.undo

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.StashEntry
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.PassThroughChangeRecordingOrder
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val HEAD = commitId(2)
private val PARENT = commitId(1)

/** 기록 뒤 앱 내부의 다른 조작이 브랜치를 옮긴 자리. 기록에 이 값이 새어 들어오면 안 된다. */
private val MOVED_AFTERWARDS = commitId(9)

private val RECORDED_AT = Instant.parse("2026-08-25T01:02:03Z")
private val FIXED_CLOCK: Clock = Clock.fixed(RECORDED_AT, ZoneOffset.UTC)

/** 변경 연산이 자기 임계 구역 안에서 캡처해 결과로 준 기준 상태. */
private val CAPTURED = RepositoryBaseline(branch = MAIN, head = HEAD)

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
 *
 * 되돌릴 수 있는 기록의 기준 상태는 **호출부가 넘긴다** (UND-73). 여기서 다시 읽으면 그 읽기가
 * 변경과 다른 임계 구역이라, 사이에 낀 앱 내부의 다른 조작까지 반영된 상태가 남는다.
 */
class OperationRecorderSpec : BehaviorSpec({

    Given("변경 연산이 기준 상태를 결과로 준 기록") {
        // 기록 시점에는 이미 다른 조작이 브랜치를 옮겨 놓았다 — 여기서 읽으면 그 값이 기록된다.
        val refGateway = refGatewayOn(branch(MAIN, MOVED_AFTERWARDS, isCurrent = true))

        When("커밋을 기록하면") {
            val stack = UndoStack()
            val recorded = OperationRecorder(refGateway, stack, FIXED_CLOCK, PassThroughChangeRecordingOrder).record(
                operation = GitOperationKind.COMMIT,
                strategy = UndoStrategy.SoftResetTo(PARENT),
                baseline = CAPTURED,
            )

            Then("전달받은 기준 상태를 그대로 남긴다") {
                recorded shouldBe OperationEntry(
                    operation = GitOperationKind.COMMIT,
                    strategy = UndoStrategy.SoftResetTo(PARENT),
                    baseline = CAPTURED,
                    targetLabel = GitOperationKind.COMMIT.label,
                    recordedAt = RECORDED_AT,
                )
                stack.peek() shouldBe recorded
            }

            Then("기준 상태를 사후에 다시 읽지 않는다 — 그 읽기가 창을 연다") {
                coVerify(exactly = 0) { refGateway.listBranches() }
            }
        }

        When("여러 연산을 기록하면") {
            val stack = UndoStack()
            val recorder = OperationRecorder(refGateway, stack, changeRecordingOrder = PassThroughChangeRecordingOrder)
            val first =
                recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE), CAPTURED)
            val second =
                recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(STASH), CAPTURED)

            Then("최신 기록이 스택 최상단이다") {
                stack.history() shouldContainExactly listOf(second, first)
            }
        }
    }

    Given("브랜치 위가 아닐 때 캡처된 기준 상태") {
        val refGateway = refGatewayOn(branch(MAIN, HEAD, isCurrent = true))
        val detached = RepositoryBaseline(branch = null, head = null)

        When("연산을 기록하면") {
            val stack = UndoStack()
            OperationRecorder(refGateway, stack, changeRecordingOrder = PassThroughChangeRecordingOrder).record(
                operation = GitOperationKind.CHERRY_PICK,
                strategy = UndoStrategy.HardResetTo(MAIN, previous = PARENT, expected = HEAD),
                baseline = detached,
            )

            Then("브랜치 없음을 그대로 기록한다 — 되돌리기 단계에서 거부할 근거가 된다") {
                stack.peek()?.baseline shouldBe detached
            }
        }
    }

    Given("복구 불가 연산") {
        val refGateway = refGatewayOn(branch(MAIN, HEAD, isCurrent = true), branch(FEATURE, PARENT, false))

        When("사유와 함께 기록하면") {
            val stack = UndoStack()
            val recorder = OperationRecorder(refGateway, stack, FIXED_CLOCK, PassThroughChangeRecordingOrder)
            val recorded = recorder.recordIrreversible(
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

            Then("기준 상태는 여기서 읽는다 — 되돌리기 판단에 쓰이지 않아 닫을 창이 없다") {
                recorded.baseline shouldBe RepositoryBaseline(branch = MAIN, head = HEAD)
            }
        }
    }
})
