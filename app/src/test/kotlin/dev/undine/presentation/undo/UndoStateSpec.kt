package dev.undine.presentation.undo

import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoService
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Instant

private val MAIN = RefName("main")
private val HEAD = CommitId.of("a".repeat(40))
private val PARENT = CommitId.of("b".repeat(40))

private fun undoEntry() = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(PARENT),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "로그인 수정",
    recordedAt = Instant.parse("2026-08-25T01:02:03Z"),
)

private fun currentBranch() = Branch(
    name = MAIN,
    target = HEAD,
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

    init {
        coEvery { refGateway.listBranches() } returns listOf(currentBranch())
        coEvery { repositoryGateway.status() } returns
            WorkingTreeStatus(emptyList(), emptyList(), emptyList(), emptyList())
        coEvery { worktreeOpsGateway.reset(any(), any()) } just Runs
    }

    private val service = UndoService(stack, refGateway, repositoryGateway, worktreeOpsGateway)
    val state = UndoState(
        scope = CoroutineScope(Dispatchers.Unconfined),
        peekUndoTarget = PeekUndoTargetUseCase(service),
        loadUndoHistory = LoadUndoHistoryUseCase(stack),
        undoLastOperation = UndoLastOperationUseCase(service),
    )
}

/** Undo 상태 홀더 — 새 Compose UI 런타임 없이 상태 전이와 재진입 차단을 검증한다. */
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
        fixture.state.lastOutcome.shouldBeInstanceOf<UndoOutcome.Undone>().operation shouldBe GitOperationKind.COMMIT
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
        runBlocking { yield() }

        fixture.state.isUndoing shouldBe false
        fixture.state.target shouldBe UndoTarget.None
    }
})
