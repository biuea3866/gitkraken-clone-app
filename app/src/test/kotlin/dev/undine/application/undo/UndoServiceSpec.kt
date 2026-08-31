package dev.undine.application.undo

import dev.undine.domain.Branch
import dev.undine.domain.ChangeType
import dev.undine.domain.CheckoutResult
import dev.undine.domain.CommitId
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.FileChange
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
import dev.undine.domain.StashEntry
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
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

/** 변경 Gateway 가 결과로 주는 "변경 직후 기준 상태" 자리. 되돌리기 경로는 이 값을 소비하지 않는다. */
private val BASELINE = RepositoryBaseline(branch = MAIN, head = HEAD)

/** 첫 변경 **뒤에** 앱 안의 두 번째 변경이 옮겨 놓은 자리. */
private val AFTER_SECOND_CHANGE = commitId(7)

/** 기록 시점의 stash. 되돌리기는 이 항목을 target 으로 지목해야 한다. */
private val RECORDED_STASH = StashEntry(
    index = 0,
    message = "작업 중",
    target = commitId(3),
    createdAt = Instant.parse("2026-01-02T03:04:05Z"),
    includedUntracked = false,
)

/** 어느 스택에도 올라간 적 없는 기록. "대상이 어긋났다" 를 재현하는 데 쓴다. */
private val GHOST_ENTRY = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(PARENT),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "지나간 세션의 커밋",
    recordedAt = Instant.parse("2026-01-02T03:04:05Z"),
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
        // 체크아웃도 변경 직후 기준 상태와 이전 위치를 결과로 준다 (UND-73) — 되돌리기는 그 값을 쓰지 않는다.
        coEvery { refGateway.checkout(any(), any()) } returns
            CheckoutResult(previousRef = FEATURE, baseline = BASELINE)
        coEvery { refGateway.deleteBranch(any(), any()) } returns DeleteBranchResult.DELETED
        coEvery { worktreeOpsGateway.reset(any(), any()) } just Runs
        coEvery { worktreeOpsGateway.hardReset(any()) } just Runs
        // 되돌리기용 이동·reset 도 변경 직후 기준 상태를 돌려준다 (UND-73) — 되돌리기는 그 값을 쓰지 않는다.
        coEvery { worktreeOpsGateway.hardResetBranch(any(), any(), any()) } returns BASELINE
        coEvery { refGateway.moveBranch(any(), any(), any()) } returns BASELINE
        coEvery { refGateway.moveTag(any(), any(), any()) } returns BASELINE
        coEvery { worktreeOpsGateway.stashApply(any()) } just Runs
        coEvery { worktreeOpsGateway.stashDrop(any()) } just Runs
    }

    val recorder = OperationRecorder(refGateway, stack)

    /**
     * 변경 Gateway 가 결과로 주는 **변경 직후 기준 상태**를 흉내 낸다 (UND-73). 이 스펙의 Gateway 는
     * 대역이라 결과 계약을 흉내 낼 곳이 없으므로, 기록 시점의 저장소 상태를 그대로 캡처해 넘긴다.
     */
    suspend fun record(operation: GitOperationKind, strategy: UndoStrategy.Reversible): OperationEntry =
        recorder.record(operation, strategy, refGateway.currentBaseline())

    val service = UndoService(
        undoStack = stack,
        refGateway = refGateway,
        repositoryGateway = repositoryGateway,
        worktreeOpsGateway = worktreeOpsGateway,
    )

    /**
     * 화면과 같은 순서로 되돌린다 — 미리 본 최상단을 **그대로 대상으로 지목**해 넘긴다.
     * 인자 없는 "마지막 것 되돌리기" 는 더 이상 없다 (wave 8 결정 G4).
     */
    suspend fun undoTop(): UndoOutcome {
        val expected = requireNotNull(stack.peek()) { "되돌릴 기록이 없습니다" }
        val execution = service.undo(expected)
        return (execution as UndoExecution.Completed).outcome
    }

    /** 저장소를 바꾸는 호출이 하나도 없었는지 본다. */
    fun verifyNoGitChange() {
        coVerify(exactly = 0) { worktreeOpsGateway.reset(any(), any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.hardReset(any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.hardResetBranch(any(), any(), any()) }
        coVerify(exactly = 0) { refGateway.moveBranch(any(), any(), any()) }
        coVerify(exactly = 0) { refGateway.moveTag(any(), any(), any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.stashApply(any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.stashDrop(any()) }
        coVerify(exactly = 0) { refGateway.checkout(any(), any()) }
        coVerify(exactly = 0) { refGateway.deleteBranch(any(), any()) }
    }
}

class UndoServiceSpec : BehaviorSpec({

    Given("아무것도 기록되지 않은 세션") {
        val fixture = UndoFixture()

        When("이미 사라진 기록의 되돌리기를 요청하면") {
            val execution = fixture.service.undo(GHOST_ENTRY)

            Then("Git 을 건드리지 않고 대상이 어긋났음을 알린다") {
                execution shouldBe UndoExecution.TargetChanged
                fixture.verifyNoGitChange()
                coVerify(exactly = 0) { fixture.refGateway.listBranches() }
            }
        }
    }

    Given("커밋을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("직전 커밋으로 soft reset 하고 항목을 소비한다") {
                outcome shouldBe UndoOutcome.Undone(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
                fixture.stack.history().shouldBeEmpty()
            }
        }
    }

    Given("체크아웃을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.CHECKOUT, UndoStrategy.CheckoutRef(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("이전 ref 를 강제 없이 다시 체크아웃한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.checkout(FEATURE, false) }
            }
        }
    }

    Given("브랜치 생성을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("만든 브랜치를 강제 없이 삭제한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.deleteBranch(FEATURE, false) }
            }
        }
    }

    Given("브랜치에 병합되지 않은 커밋이 쌓인 세션") {
        val fixture = UndoFixture()
        coEvery { fixture.refGateway.deleteBranch(any(), any()) } returns DeleteBranchResult.REFUSED_UNMERGED
        fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

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
        fixture.record(
            GitOperationKind.MERGE,
            UndoStrategy.HardResetTo(MAIN, previous = PARENT, expected = HEAD),
        )

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("기록된 브랜치를 ORIG_HEAD 로 조건부 hard reset 한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.hardResetBranch(MAIN, PARENT, HEAD) }
            }
        }
    }

    Given("stash 저장을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("기록한 stash 를 지목해 적용하고 그 항목만 지운다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.stashApply(RECORDED_STASH) }
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.stashDrop(RECORDED_STASH) }
            }
        }
    }

    Given("stash 를 기록한 뒤 밖에서 stash 가 하나 더 쌓인 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))
        val newer = RECORDED_STASH.copy(index = 0, message = "밖에서 만든 stash", target = commitId(9))

        When("되돌리기를 요청하면") {
            fixture.undoTop()

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
        fixture.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(RECORDED_STASH))

        When("되돌리기를 요청하면") {
            Then("적용에 실패한 stash 를 지우지 않는다") {
                shouldThrow<UndineException.GitOperationFailed> { fixture.undoTop() }
                coVerify(exactly = 0) { fixture.worktreeOpsGateway.stashDrop(any()) }
            }
        }
    }

    Given("push 를 복구 불가로 기록한 세션") {
        val fixture = UndoFixture()
        val pushEntry =
            fixture.recorder.recordIrreversible(GitOperationKind.PUSH, "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다")

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("조용히 성공하지 않고 사유를 돌려주며 Git 을 건드리지 않는다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.Irreversible>()
                refused.operation shouldBe GitOperationKind.PUSH
                refused.reason shouldContain "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다"
                fixture.verifyNoGitChange()
            }
        }

        When("같은 항목의 되돌리기를 다시 요청하면") {
            val execution = fixture.service.undo(pushEntry)

            Then("같은 사유가 반복되지 않도록 항목이 소비돼 대상이 사라진 상태다") {
                execution shouldBe UndoExecution.TargetChanged
                fixture.stack.history().shouldBeEmpty()
            }
        }
    }

    Given("hard reset 과 stash 삭제를 복구 불가로 기록한 세션") {
        val fixture = UndoFixture()
        fixture.recorder.recordIrreversible(GitOperationKind.HARD_RESET, "hard reset 이 지운 변경은 남아 있지 않습니다")
        fixture.recorder.recordIrreversible(GitOperationKind.STASH_DROP, "지운 stash 는 되살릴 수 없습니다")

        When("두 번 되돌리기를 요청하면") {
            val first = fixture.undoTop()
            val second = fixture.undoTop()

            Then("최상단부터 각각의 사유를 돌려준다") {
                first.shouldBeInstanceOf<UndoOutcome.Irreversible>().operation shouldBe GitOperationKind.STASH_DROP
                second.shouldBeInstanceOf<UndoOutcome.Irreversible>().operation shouldBe GitOperationKind.HARD_RESET
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("첫 변경과 그 기록 사이에 앱 안의 두 번째 변경이 끼어든 세션") {
        // 기록 시점의 조회는 이미 두 번째 변경까지 반영돼 있다 — 기록이 여기서 읽으면 그 값이 남는다.
        val fixture = UndoFixture(branches = listOf(branch(MAIN, AFTER_SECOND_CHANGE, isCurrent = true)))
        // 첫 변경의 Gateway 결과가 준 "내 변경 직후" 기준 상태로 기록한다 (UND-73).
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT), BASELINE)

        When("첫 항목의 되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("두 번째 변경 위에서 실행하지 않고 외부 변경으로 거부한다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.ExternalChange>()
                refused.recorded shouldBe BASELINE
                refused.current shouldBe RepositoryBaseline(branch = MAIN, head = AFTER_SECOND_CHANGE)
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("변경 직후 기준 상태로 기록한 뒤 아무도 끼어들지 않은 세션") {
        val fixture = UndoFixture()
        fixture.recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT), BASELINE)

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("간섭이 없으므로 그대로 성공한다") {
                outcome shouldBe UndoOutcome.Undone(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
            }
        }
    }

    Given("기록 뒤 앱 밖에서 HEAD 가 움직인 저장소") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, commitId(9), isCurrent = true))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("Git 을 바꾸지 않고 외부 변경 사유를 돌려준다") {
                outcome.shouldBeInstanceOf<UndoOutcome.ExternalChange>().reason shouldContain "밖"
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("detached HEAD 상태의 저장소") {
        val fixture = UndoFixture(branches = listOf(branch(MAIN, HEAD, isCurrent = true)))
        fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, HEAD, isCurrent = false))

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("브랜치 위가 아니라는 사유로 거부한다") {
                outcome.shouldBeInstanceOf<UndoOutcome.NoCurrentBranch>().reason shouldContain "detached"
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("커밋되지 않은 변경이 있는 저장소") {
        val fixture = UndoFixture(status = DIRTY)
        fixture.record(
            GitOperationKind.REBASE,
            UndoStrategy.HardResetTo(MAIN, previous = PARENT, expected = HEAD),
        )

        When("ORIG_HEAD 복구를 요청하면") {
            val outcome = fixture.undoTop()

            Then("사용자의 미커밋 작업을 삼키지 않고 거부한다") {
                val refused = outcome.shouldBeInstanceOf<UndoOutcome.UncommittedChanges>()
                refused.paths shouldBe listOf("app.kt")
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("복구 불가로 기록된 최상단") {
        val fixture = UndoFixture()
        val pushEntry = fixture.recorder.recordIrreversible(GitOperationKind.PUSH, "원격에 이미 반영됨")

        When("사용자 확인 뒤 이력에서 지우면") {
            val execution = fixture.service.discardBlocked(pushEntry)

            Then("저장소를 건드리지 않고 그 기록만 소비한다") {
                val discarded = execution.shouldBeInstanceOf<UndoExecution.Discarded>()
                discarded.entry shouldBe pushEntry
                discarded.refusal.shouldBeInstanceOf<UndoOutcome.Irreversible>()
                fixture.stack.history().shouldBeEmpty()
                fixture.verifyNoGitChange()
                coVerify(exactly = 0) { fixture.repositoryGateway.status() }
            }
        }
    }

    Given("기록 뒤 앱 밖에서 HEAD 가 움직여 막힌 최상단") {
        val fixture = UndoFixture()
        val olderEntry = fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
        val entry = fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, commitId(9), isCurrent = true))

        When("그 기록을 지우려 하면") {
            val execution = fixture.service.discardBlocked(entry)

            Then("해소되면 되돌릴 수 있는 기록이므로 하위 항목까지 건너뛰지 않고 그대로 둔다") {
                execution shouldBe UndoExecution.TargetChanged
                fixture.stack.history() shouldBe listOf(entry, olderEntry)
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("detached HEAD 때문에 막힌 최상단") {
        val fixture = UndoFixture()
        val olderEntry = fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
        val entry = fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))
        coEvery { fixture.refGateway.listBranches() } returns listOf(branch(MAIN, HEAD, isCurrent = false))

        When("그 기록을 지우려 하면") {
            val execution = fixture.service.discardBlocked(entry)

            Then("브랜치로 돌아오면 되돌릴 수 있으므로 하위 항목까지 건너뛰지 않고 그대로 둔다") {
                execution shouldBe UndoExecution.TargetChanged
                fixture.stack.history() shouldBe listOf(entry, olderEntry)
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("미커밋 변경 때문에 막힌 hard reset 최상단") {
        val fixture = UndoFixture(status = DIRTY)
        val olderEntry = fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
        val entry = fixture.record(
            GitOperationKind.MERGE,
            UndoStrategy.HardResetTo(MAIN, previous = PARENT, expected = HEAD),
        )

        When("그 기록을 지우려 하면") {
            val execution = fixture.service.discardBlocked(entry)

            Then("변경을 정리하면 되돌릴 수 있으므로 하위 항목까지 건너뛰지 않고 그대로 둔다") {
                execution shouldBe UndoExecution.TargetChanged
                fixture.stack.history() shouldBe listOf(entry, olderEntry)
                fixture.verifyNoGitChange()
            }
        }
    }

    Given("두 연산을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(FEATURE))
        fixture.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT))

        When("한 번 되돌리기를 요청하면") {
            fixture.undoTop()

            Then("최상단 하나만 되돌리고 나머지는 스택에 남는다") {
                coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
                coVerify(exactly = 0) { fixture.refGateway.deleteBranch(any(), any()) }
                fixture.stack.history().map { it.operation } shouldBe listOf(GitOperationKind.BRANCH_CREATE)
            }
        }
    }

    Given("브랜치 이동을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(
            GitOperationKind.BRANCH_MOVE,
            UndoStrategy.MoveBranchTo(FEATURE, previous = PARENT, expected = HEAD),
        )

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("기대 위치를 함께 넘겨 조건부로 이전 위치로 되돌린다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.moveBranch(FEATURE, PARENT, HEAD) }
            }
        }
    }

    Given("태그 이동을 기록한 세션") {
        val fixture = UndoFixture()
        fixture.record(
            GitOperationKind.TAG_MOVE,
            UndoStrategy.MoveTagTo(FEATURE, previous = PARENT, expected = HEAD),
        )

        When("되돌리기를 요청하면") {
            val outcome = fixture.undoTop()

            Then("기대 위치를 함께 넘겨 조건부로 이전 위치로 되돌린다") {
                outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
                coVerify(exactly = 1) { fixture.refGateway.moveTag(FEATURE, PARENT, HEAD) }
            }
        }
    }

    Given("기록 이후 대상 ref 가 다른 곳으로 옮겨진 저장소") {
        val fixture = UndoFixture()
        val mismatch = UndineException.StateViolation("기대한 위치와 달라 참조를 옮기지 않았습니다: feature")
        coEvery { fixture.refGateway.moveBranch(any(), any(), any()) } throws mismatch
        fixture.record(
            GitOperationKind.BRANCH_MOVE,
            UndoStrategy.MoveBranchTo(FEATURE, previous = PARENT, expected = HEAD),
        )

        When("되돌리기를 요청하면") {
            val failure = shouldThrow<UndineException.StateViolation> { fixture.undoTop() }

            Then("성공으로 숨기지 않고 거부 사유를 그대로 올린다") {
                failure.detail shouldContain FEATURE.value
            }
        }
    }
})
