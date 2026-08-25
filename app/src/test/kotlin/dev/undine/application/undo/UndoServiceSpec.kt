package dev.undine.application.undo

import dev.undine.domain.Branch
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.FileChange
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
import dev.undine.domain.StashEntry
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import java.time.Instant

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val HEAD = commitId(2)
private val PARENT = commitId(1)

/** 기록 시점의 stash. 되돌리기는 이 항목을 target 으로 지목해야 한다. */
private val RECORDED_STASH = StashEntry(
    index = 0,
    message = "작업 중",
    target = commitId(3),
    createdAt = Instant.parse("2026-01-02T03:04:05Z"),
    includedUntracked = false,
)

private val CLEAN = WorkingTreeStatus(emptyList(), emptyList(), emptyList(), emptyList())
private val DIRTY = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = listOf(
        FileChange(
            path = "app.kt",
            previousPath = null,
            changeType = ChangeType.MODIFIED,
            addedLines = 1,
            deletedLines = 0,
            isBinary = false,
        ),
    ),
    untracked = emptyList(),
    conflicted = emptyList(),
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

/**
 * 되돌리기 대역 — Gateway 는 domain interface 의 대역이고, JGit 이 실제로 그렇게 동작하는지는
 * [UndoRepositorySpec] 이 실제 임시 저장소로 본다.
 *
 * 거부 경로에서 **변경 Gateway 를 부르지 않는 것**이 이 스펙의 핵심이다.
 */
private class UndoFixture(
    branches: List<Branch> = listOf(branch(MAIN, HEAD, isCurrent = true)),
    status: WorkingTreeStatus = CLEAN,
) {
    val stack = UndoStack()
    val refGateway = mockk<RefGateway>()
    val repositoryGateway = mockk<RepositoryGateway>()
    val worktreeOpsGateway = mockk<WorktreeOpsGateway>()

    init {
        coEvery { refGateway.listBranches() } returns branches
        coEvery { repositoryGateway.status() } returns status
        coEvery { refGateway.checkout(any(), any()) } just Runs
        coEvery { refGateway.deleteBranch(any(), any()) } returns DeleteBranchResult.DELETED
        coEvery { worktreeOpsGateway.reset(any(), any()) } just Runs
        coEvery { worktreeOpsGateway.hardReset(any()) } just Runs
        coEvery { worktreeOpsGateway.stashApply(any()) } just Runs
        coEvery { worktreeOpsGateway.stashDrop(any()) } just Runs
    }

    val recorder = OperationRecorder(refGateway, stack)

    val service = UndoService(
        undoStack = stack,
        refGateway = refGateway,
        repositoryGateway = repositoryGateway,
        worktreeOpsGateway = worktreeOpsGateway,
    )

    /** 저장소를 바꾸는 호출이 하나도 없었는지 본다. */
    fun verifyNoGitChange() {
        coVerify(exactly = 0) { worktreeOpsGateway.reset(any(), any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.hardReset(any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.stashApply(any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.stashDrop(any()) }
        coVerify(exactly = 0) { refGateway.checkout(any(), any()) }
        coVerify(exactly = 0) { refGateway.deleteBranch(any(), any()) }
    }
}

class UndoServiceSpec : BehaviorSpec({

    Given("아무것도 기록되지 않은 세션") {
        val fixture = UndoFixture()

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("Git 을 건드리지 않고 빈 스택임을 알린다") {
                outcome shouldBe UndoOutcome.NothingToUndo
                fixture.verifyNoGitChange()
                coVerify(exactly = 0) { fixture.refGateway.listBranches() }
            }
        }
    }

    Given("커밋을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("직전 커밋으로 soft reset 하고 항목을 소비한다") {
                outcome shouldBe UndoOutcome.Undone(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
                fixture.stack.history().shouldBeEmpty()
            }
        }
    }

    Given("체크아웃을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.CHECKOUT, UndoStrategy.CheckoutRef(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("이전 ref 를 강제 없이 다시 체크아웃한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.checkout(FEATURE, false) }
            }
        }
    }

    Given("브랜치 생성을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("만든 브랜치를 강제 없이 삭제한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.deleteBranch(FEATURE, false) }
            }
        }
    }

    Given("브랜치에 병합되지 않은 커밋이 쌓인 세션") {
        val fixture = UndoFixture()
        coEvery { fixture.refGateway.deleteBranch(any(), any()) } returns DeleteBranchResult.REFUSED_UNMERGED
        fixture.recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("강제 삭제로 승격하지 않고 사유를 돌려준다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.UnmergedBranch>()
                refused.branch shouldBe FEATURE
                refused.reason shouldContain FEATURE.value
                coVerify(exactly = 0) { fixture.refGateway.deleteBranch(FEATURE, true) }
            }
        }
    }

    Given("병합을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.MERGE, UndoStrategy.HardResetTo(PARENT))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("기록된 ORIG_HEAD 로 hard reset 한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.hardReset(PARENT) }
            }
        }
    }

    Given("stash 저장을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("기록한 stash 를 지목해 적용하고 그 항목만 지운다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.stashApply(RECORDED_STASH) }
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.stashDrop(RECORDED_STASH) }
            }
        }
    }

    Given("stash 를 기록한 뒤 밖에서 stash 가 하나 더 쌓인 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))
        val newer = RECORDED_STASH.copy(index = 0, message = "밖에서 만든 stash", target = commitId(9))

        When("되돌리기를 요청하면") {
            fixture.service.undo()

            Then("최신 stash 가 아니라 기록한 stash 만 건드린다") {
                coVerify(exactly = 0) { fixture.worktreeOpsGateway.stashApply(newer) }
                coVerify(exactly = 0) { fixture.worktreeOpsGateway.stashDrop(newer) }
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.stashApply(RECORDED_STASH) }
            }
        }
    }

    Given("적용이 실패하는 stash 를 기록한 세션") {
        val fixture = UndoFixture()
        coEvery { fixture.worktreeOpsGateway.stashApply(any()) } throws
            UndineException.GitOperationFailed("worktreeops.stashApply", IllegalStateException("충돌"))
        fixture.recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))

        When("되돌리기를 요청하면") {
            Then("적용에 실패한 stash 를 지우지 않는다") {
                shouldThrow<UndineException.GitOperationFailed> { fixture.service.undo() }
                coVerify(exactly = 0) { fixture.worktreeOpsGateway.stashDrop(any()) }
            }
        }
    }

    Given("push 를 복구 불가로 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.recordIrreversible(GitOperationKind.PUSH, "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다")

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("조용히 성공하지 않고 사유를 돌려주며 Git 을 건드리지 않는다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.Irreversible>()
                refused.operation shouldBe GitOperationKind.PUSH
                refused.reason shouldContain "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다"
                fixture.verifyNoGitChange()
            }
        }

        When("다시 되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("같은 사유가 반복되지 않도록 항목이 소비돼 빈 스택이 된다") {
                outcome shouldBe UndoOutcome.NothingToUndo
            }
        }
    }

    Given("hard reset 과 stash 삭제를 복구 불가로 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.recordIrreversible(GitOperationKind.HARD_RESET, "hard reset 이 지운 변경은 남아 있지 않습니다")
        fixture.recorder.recordIrreversible(GitOperationKind.STASH_DROP, "지운 stash 는 되살릴 수 없습니다")

        When("두 번 되돌리기를 요청하면") {
            val first = fixture.service.undo()
            val second = fixture.service.undo()

            Then("최상단부터 각각의 사유를 돌려준다") {
                first.shouldBeInstanceOf<UndoOutcome.Irreversible>().operation shouldBe GitOperationKind.STASH_DROP
                second.shouldBeInstanceOf<UndoOutcome.Irreversible>().operation shouldBe GitOperationKind.HARD_RESET
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("기록 뒤 앱 밖에서 HEAD 가 움직인 저장소") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, commitId(9), isCurrent = true))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("Git 을 바꾸지 않고 외부 변경 사유를 돌려준다") {
                outcome.shouldBeInstanceOf<UndoOutcome.ExternalChange>().reason shouldContain "밖"
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("detached HEAD 상태의 저장소") {
        val fixture = UndoFixture(branches = listOf(branch(MAIN, HEAD, isCurrent = true)))
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, HEAD, isCurrent = false))

        When("되돌리기를 요청하면") {
            val outcome = fixture.service.undo()

            Then("브랜치 위가 아니라는 사유로 거부한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.NoCurrentBranch>().reason shouldContain "detached"
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("커밋되지 않은 변경이 있는 저장소") {
        val fixture = UndoFixture(status = DIRTY)
        fixture.recorder.record(GitOperationKind.REBASE, UndoStrategy.HardResetTo(PARENT))

        When("ORIG_HEAD 복구를 요청하면") {
            val outcome = fixture.service.undo()

            Then("사용자의 미커밋 작업을 삼키지 않고 거부한다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.UncommittedChanges>()
                refused.paths shouldBe listOf("app.kt")
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("두 연산을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))

        When("한 번 되돌리기를 요청하면") {
            fixture.service.undo()

            Then("최상단 하나만 되돌리고 나머지는 스택에 남는다") {
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
                coVerify(exactly = 0) { fixture.refGateway.deleteBranch(any(), any()) }
                fixture.stack.history().map { it.operation } shouldBe listOf(GitOperationKind.BRANCH_CREATE)
            }
        }
    }
})
