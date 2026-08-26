package dev.undine.presentation.undo

import dev.undine.application.undo.DiscardBlockedUndoEntryUseCase
import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoService
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.Branch
import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.RepositoryBaseline
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Instant

private val MAIN = RefName("main")
private val HEAD = CommitId.of("a".repeat(40))
private val PARENT = CommitId.of("b".repeat(40))
private val MOVED_HEAD = CommitId.of("c".repeat(40))

private fun undoEntry() = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(PARENT),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "로그인 수정",
    recordedAt = Instant.parse("2026-08-25T01:02:03Z"),
)

private fun irreversibleEntry() = OperationEntry(
    operation = GitOperationKind.PUSH,
    strategy = UndoStrategy.Irreversible("원격에 이미 반영됨"),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "origin/main",
    recordedAt = Instant.parse("2026-08-25T01:02:04Z"),
)

/** 워킹트리를 덮어쓰는 되돌리기 — 미커밋 변경이 있으면 거부된다. */
private fun hardResetEntry() = OperationEntry(
    operation = GitOperationKind.MERGE,
    strategy = UndoStrategy.HardResetTo(PARENT),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "feature 병합",
    recordedAt = Instant.parse("2026-08-25T01:02:05Z"),
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

private fun branchAt(head: CommitId) = Branch(
    name = MAIN,
    target = head,
    isCurrent = true,
    isRemote = false,
    upstream = null,
    ahead = 0,
    behind = 0,
)

private class UndoStateFixture {
    val stack = UndoStack()
    val refGateway = mockk<RefGateway>()
    val repositoryGateway = mockk<RepositoryGateway>()
    val worktreeOpsGateway = mockk<WorktreeOpsGateway>()

    /** 앱 밖에서 브랜치가 움직인 상황을 재현하려고 조회 결과를 바꿀 수 있게 둔다. */
    var currentHead: CommitId = HEAD

    /** 체크아웃된 로컬 브랜치가 없는 상태(detached HEAD)를 재현한다. */
    var onBranch: Boolean = true

    /** 워킹트리를 덮어쓰는 되돌리기를 막는 미커밋 변경을 재현한다. */
    var status: WorkingTreeStatus = CLEAN

    val job = Job()
    val scope = CoroutineScope(Dispatchers.Unconfined + job)

    init {
        coEvery { refGateway.listBranches() } coAnswers {
            listOf(branchAt(currentHead).copy(isCurrent = onBranch))
        }
        coEvery { repositoryGateway.status() } coAnswers { status }
        coEvery { worktreeOpsGateway.reset(any(), any()) } just Runs
        coEvery { worktreeOpsGateway.hardReset(any()) } just Runs
    }

    /** 저장소를 바꾸는 호출이 하나도 없었는지 본다. */
    fun verifyNoGitChange() {
        coVerify(exactly = 0) { worktreeOpsGateway.reset(any(), any()) }
        coVerify(exactly = 0) { worktreeOpsGateway.hardReset(any()) }
    }

    private val service = UndoService(stack, refGateway, repositoryGateway, worktreeOpsGateway)
    val state = UndoState(
        scope = scope,
        peekUndoTarget = PeekUndoTargetUseCase(service),
        loadUndoHistory = LoadUndoHistoryUseCase(stack),
        undoLastOperation = UndoLastOperationUseCase(service),
        discardBlockedUndoEntry = DiscardBlockedUndoEntryUseCase(service),
    )
}

private fun settle() = runBlocking { yield() }

/**
 * Undo 상태 홀더 — 새 Compose UI 런타임 없이 상태 전이·재진입 차단·실패 경로를 검증한다.
 *
 * 실제 [UndoService] 와 실제 [UndoStack] 을 함께 돌린다. 상태 홀더만 mock 으로 감싸면 "미리 본
 * 대상과 실제 대상이 어긋난다" 같은 결함이 조합 지점에 숨어 잡히지 않는다.
 */
class UndoStateSpec : FunSpec({

    test("새로고침은 최상단 대상과 최신 우선 이력을 읽는다") {
        val fixture = UndoStateFixture()
        val older = undoEntry().copy(targetLabel = "첫 커밋", recordedAt = Instant.parse("2026-08-25T01:02:02Z"))
        val newest = undoEntry()
        fixture.stack.record(older)
        fixture.stack.record(newest)

        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.Undoable(newest)
        fixture.state.history shouldContainExactly listOf(newest, older)
    }

    test("키보드 경로는 버튼과 같은 한 단계 undo를 한 번만 실행하고 이력을 갱신한다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.state.refresh()

        fixture.state.undoFromKeyboard()

        coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
        fixture.state.history shouldContainExactly emptyList()
        fixture.state.target shouldBe UndoTarget.None
        val completed = fixture.state.lastExecution.shouldBeInstanceOf<UndoExecution.Completed>()
        completed.outcome.shouldBeInstanceOf<UndoOutcome.Undone>().operation shouldBe GitOperationKind.COMMIT
    }

    test("실행 중 재진입은 막고 완료 뒤에만 버튼을 다시 판단한다") {
        val fixture = UndoStateFixture()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.worktreeOpsGateway.reset(any(), any()) } coAnswers { release.await() }
        fixture.stack.record(undoEntry())
        fixture.state.refresh()

        fixture.state.undo()
        fixture.state.isUndoing shouldBe true
        fixture.state.canUndo shouldBe false
        fixture.state.undo()

        coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
        release.complete(Unit)
        settle()

        fixture.state.isUndoing shouldBe false
        fixture.state.target shouldBe UndoTarget.None
    }

    // --- 미리 본 대상과 실제 대상 (1차 리뷰 p1 — UndoState 실행 대상 어긋남) ---

    test("미리 본 뒤 다른 연산이 기록되면 새 최상단을 되돌리지 않는다") {
        val fixture = UndoStateFixture()
        val previewed = undoEntry()
        fixture.stack.record(previewed)
        fixture.state.refresh()

        // 사용자가 버튼을 누르기 직전, 앱의 다른 연산이 기록을 남겼다.
        val recordedLater = undoEntry().copy(
            strategy = UndoStrategy.SoftResetTo(MOVED_HEAD),
            targetLabel = "뒤늦게 들어온 커밋",
            recordedAt = Instant.parse("2026-08-25T01:03:00Z"),
        )
        fixture.stack.record(recordedLater)

        fixture.state.undo()
        settle()

        coVerify(exactly = 0) { fixture.worktreeOpsGateway.reset(any(), any()) }
        fixture.state.lastExecution shouldBe UndoExecution.TargetChanged
        fixture.stack.size shouldBe 2
        // 실행하지 않고 다시 읽어, 사용자가 지금의 대상을 보고 다시 판단하게 한다.
        fixture.state.target shouldBe UndoTarget.Undoable(recordedLater)
    }

    // --- 취소 (1차 리뷰 p1 — Compose scope 취소가 기록을 잃는 문제) ---

    test("실행 중 화면 scope 이 취소돼도 소비한 기록의 되돌리기는 끝까지 간다") {
        val fixture = UndoStateFixture()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.worktreeOpsGateway.reset(any(), any()) } coAnswers { release.await() }
        fixture.stack.record(undoEntry())
        fixture.state.refresh()

        fixture.state.undo()
        // 리컴포지션·화면 이탈로 상태 홀더의 scope 이 죽는다.
        fixture.scope.cancel()
        release.complete(Unit)
        settle()

        // 기록만 사라지고 저장소는 그대로인 상태가 남지 않는다.
        coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
        fixture.stack.size shouldBe 0
    }

    // --- 실패 (1차 리뷰 p1 — 예외가 재조회를 건너뛰는 문제) ---

    test("되돌리기 실행이 실패하면 실패를 화면 상태로 남기고 다시 읽는다") {
        val fixture = UndoStateFixture()
        coEvery { fixture.worktreeOpsGateway.reset(any(), any()) } throws
            UndineException.StateViolation("reset 중 인덱스가 잠겨 있습니다")
        fixture.stack.record(undoEntry())
        fixture.state.refresh()

        fixture.state.undo()
        settle()

        val failed = fixture.state.lastExecution.shouldBeInstanceOf<UndoExecution.Failed>()
        failed.entry shouldBe undoEntry()
        failed.cause.shouldBeInstanceOf<UndineException.StateViolation>()
        fixture.state.isUndoing shouldBe false
        // 실패 뒤에도 실제 스택·저장소 상태를 반영한다 — 이미 소비된 기록을 계속 보여주지 않는다.
        fixture.state.target shouldBe UndoTarget.None
        fixture.state.history shouldContainExactly emptyList()
    }

    test("실행 전 저장소 조회가 실패해도 화면은 실패를 말하고 기록은 남는다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.state.refresh()
        // 판단에 필요한 조회가 실패한다 — Git 은 아직 전혀 건드리지 않았다.
        coEvery { fixture.refGateway.listBranches() } throws
            UndineException.StateViolation("저장소를 읽을 수 없습니다")

        fixture.state.undo()
        settle()

        coVerify(exactly = 0) { fixture.worktreeOpsGateway.reset(any(), any()) }
        fixture.state.lastExecution.shouldBeInstanceOf<UndoExecution.Failed>()
        fixture.stack.size shouldBe 1
        fixture.state.isUndoing shouldBe false
        // 이어지는 재조회까지 실패하면 그 사실도 화면에 남는다 — 예외가 코루틴 밖으로 새지 않는다.
        fixture.state.loadFailure.shouldBeInstanceOf<UndineException.StateViolation>()
        fixture.state.canUndo shouldBe false
    }

    test("대상 조회가 실패하면 낡은 대상을 들고 있지 않고 사유를 남긴다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.state.refresh()
        fixture.state.canUndo shouldBe true

        coEvery { fixture.refGateway.listBranches() } throws
            UndineException.StateViolation("저장소를 읽을 수 없습니다")
        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.None
        fixture.state.canUndo shouldBe false
        fixture.state.loadFailure.shouldBeInstanceOf<UndineException.StateViolation>()
        // 이력은 메모리 스택이라 조회 실패와 무관하게 최신이다.
        fixture.state.history shouldContainExactly listOf(undoEntry())
    }

    test("다시 읽는 데 성공하면 이전 조회 실패 안내는 사라진다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        coEvery { fixture.refGateway.listBranches() } throws
            UndineException.StateViolation("저장소를 읽을 수 없습니다")
        fixture.state.refresh()
        fixture.state.loadFailure shouldNotBe null

        coEvery { fixture.refGateway.listBranches() } coAnswers { listOf(branchAt(fixture.currentHead)) }
        fixture.state.refresh()

        fixture.state.loadFailure shouldBe null
        fixture.state.target shouldBe UndoTarget.Undoable(undoEntry())
    }

    // --- 막힌 최상단 (1차 리뷰 p1 — 아래의 유효한 항목에 도달할 수 없는 문제) ---

    test("복구 불가 최상단은 미리 보기가 소비하지 않고 버튼만 잠근다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.stack.record(irreversibleEntry())

        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.Blocked(
            irreversibleEntry(),
            UndoOutcome.Irreversible(GitOperationKind.PUSH, "원격에 이미 반영됨"),
        )
        fixture.state.canUndo shouldBe false
        fixture.state.canDiscardBlocked shouldBe true
        fixture.stack.size shouldBe 2
    }

    test("막힌 최상단을 지우면 그 아래의 되돌릴 수 있는 기록에 도달한다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.stack.record(irreversibleEntry())
        fixture.state.refresh()

        fixture.state.discardBlocked()
        settle()

        fixture.state.lastExecution.shouldBeInstanceOf<UndoExecution.Discarded>().entry shouldBe irreversibleEntry()
        fixture.state.target shouldBe UndoTarget.Undoable(undoEntry())
        fixture.state.canUndo shouldBe true

        fixture.state.undo()
        settle()

        coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
        fixture.stack.size shouldBe 0
    }

    test("지우기는 저장소를 건드리지 않고 기록 한 건만 소비한다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.stack.record(irreversibleEntry())
        fixture.state.refresh()

        fixture.state.discardBlocked()
        settle()

        coVerify(exactly = 0) { fixture.worktreeOpsGateway.reset(any(), any()) }
        coVerify(exactly = 0) { fixture.worktreeOpsGateway.hardReset(any()) }
        fixture.stack.size shouldBe 1
    }

    // --- 상태로 막힌 기록은 보존한다 (2차 리뷰 p1 — 지운 뒤 해소되면 되돌릴 방법이 사라진다) ---

    test("외부 변경으로 막힌 최상단은 지우지 않고 남겨, 되돌아오면 되돌릴 수 있다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.currentHead = MOVED_HEAD
        fixture.state.refresh()
        fixture.state.target.shouldBeInstanceOf<UndoTarget.Blocked>()
            .refusal.shouldBeInstanceOf<UndoOutcome.ExternalChange>()

        fixture.state.canDiscardBlocked shouldBe false
        fixture.state.discardBlocked()
        settle()

        fixture.stack.size shouldBe 1
        fixture.state.lastExecution shouldBe null
        fixture.verifyNoGitChange()

        // 기준 상태가 돌아오면 남아 있던 그 기록을 그대로 되돌린다.
        fixture.currentHead = HEAD
        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.Undoable(undoEntry())
    }

    test("detached HEAD 로 막힌 최상단은 지우지 않고 남겨, 브랜치로 돌아오면 되돌릴 수 있다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.onBranch = false
        fixture.state.refresh()
        fixture.state.target.shouldBeInstanceOf<UndoTarget.Blocked>()
            .refusal.shouldBeInstanceOf<UndoOutcome.NoCurrentBranch>()

        fixture.state.canDiscardBlocked shouldBe false
        fixture.state.discardBlocked()
        settle()

        fixture.stack.size shouldBe 1
        fixture.state.lastExecution shouldBe null
        fixture.verifyNoGitChange()

        fixture.onBranch = true
        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.Undoable(undoEntry())
    }

    test("미커밋 변경으로 막힌 hard reset 기록은 지우지 않고 남겨, 정리하면 되돌릴 수 있다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(hardResetEntry())
        fixture.status = DIRTY
        fixture.state.refresh()
        fixture.state.target.shouldBeInstanceOf<UndoTarget.Blocked>()
            .refusal.shouldBeInstanceOf<UndoOutcome.UncommittedChanges>()

        fixture.state.canDiscardBlocked shouldBe false
        fixture.state.discardBlocked()
        settle()

        fixture.stack.size shouldBe 1
        fixture.state.lastExecution shouldBe null
        fixture.verifyNoGitChange()

        fixture.status = CLEAN
        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.Undoable(hardResetEntry())
    }

    test("되돌릴 것이 없으면 대상도 없고 스택을 건드리지 않는다") {
        val fixture = UndoStateFixture()

        fixture.state.refresh()

        fixture.state.target shouldBe UndoTarget.None
        fixture.state.canUndo shouldBe false
        fixture.state.canDiscardBlocked shouldBe false
        fixture.state.history shouldContainExactly emptyList()
    }

    test("외부 변경이 감지되면 미리 보기가 소비하지 않고 사유와 함께 막는다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.currentHead = MOVED_HEAD

        fixture.state.refresh()

        val blocked = fixture.state.target.shouldBeInstanceOf<UndoTarget.Blocked>()
        blocked.refusal.shouldBeInstanceOf<UndoOutcome.ExternalChange>()
        fixture.stack.size shouldBe 1
    }

    // --- 미리 보기 통과 뒤 거부 (1차 리뷰 p2 — 상태 전이 검증 누락) ---

    test("미리 보기 통과 뒤 거부되면 성공으로 표시하지 않고 대상·이력을 갱신한다") {
        val fixture = UndoStateFixture()
        val older = undoEntry().copy(targetLabel = "첫 커밋", recordedAt = Instant.parse("2026-08-25T01:02:02Z"))
        fixture.stack.record(older)
        fixture.stack.record(undoEntry())
        fixture.state.refresh()
        fixture.state.canUndo shouldBe true

        // 버튼을 누르기 직전 앱 밖에서 브랜치가 움직였다.
        fixture.currentHead = MOVED_HEAD
        fixture.state.undo()
        settle()

        coVerify(exactly = 0) { fixture.worktreeOpsGateway.reset(any(), any()) }
        val completed = fixture.state.lastExecution.shouldBeInstanceOf<UndoExecution.Completed>()
        completed.outcome.shouldBeInstanceOf<UndoOutcome.ExternalChange>()
        // 거부된 최상단은 소비되고, 화면은 그 사실을 반영한 대상·이력을 보여준다.
        fixture.state.history shouldContainExactly listOf(older)
        fixture.state.target.shouldBeInstanceOf<UndoTarget.Blocked>().entry shouldBe older
    }

    test("결과 안내를 닫아도 대상과 이력은 그대로다") {
        val fixture = UndoStateFixture()
        fixture.stack.record(undoEntry())
        fixture.state.refresh()
        fixture.state.undo()
        settle()

        fixture.state.dismissOutcome()

        fixture.state.lastExecution shouldBe null
        fixture.state.target shouldBe UndoTarget.None
    }
})
