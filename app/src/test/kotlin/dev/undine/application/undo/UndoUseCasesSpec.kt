package dev.undine.application.undo

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.RepositoryBaseline
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

private val MAIN = RefName("main")
private val HEAD = CommitId.of("a".repeat(40))
private val PARENT = CommitId.of("b".repeat(40))
private val ENTRY = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(PARENT),
    baseline = RepositoryBaseline(MAIN, HEAD),
    targetLabel = "로그인 수정",
    recordedAt = Instant.parse("2026-08-25T01:02:03Z"),
)

/** presentation이 UndoService·UndoStack을 직접 참조하지 않게 하는 얇은 application 경계. */
class UndoUseCasesSpec : FunSpec({

    test("다음 Undo 대상 UseCase는 서비스의 실행 전 판단을 그대로 전달한다") {
        val service = mockk<UndoService>()
        val expected = UndoTarget.Undoable(ENTRY)
        coEvery { service.preview() } returns expected

        PeekUndoTargetUseCase(service).execute() shouldBe expected

        coVerify(exactly = 1) { service.preview() }
    }

    test("이력 UseCase는 스택의 최신 우선 순서를 바꾸지 않는다") {
        val stack = UndoStack()
        val older = ENTRY.copy(targetLabel = "첫 커밋", recordedAt = ENTRY.recordedAt.minusSeconds(1))
        stack.record(older)
        stack.record(ENTRY)

        LoadUndoHistoryUseCase(stack).execute() shouldContainExactly listOf(ENTRY, older)
    }

    test("Undo UseCase는 미리 본 항목을 그대로 실어 보내고 결과를 가공하지 않는다") {
        val service = mockk<UndoService>()
        val expected = UndoExecution.Completed(
            UndoOutcome.Undone(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(PARENT)),
        )
        coEvery { service.undo(ENTRY) } returns expected

        UndoLastOperationUseCase(service).execute(ENTRY) shouldBe expected

        coVerify(exactly = 1) { service.undo(ENTRY) }
    }

    test("폐기 UseCase는 되돌리기가 아니라 기록 지우기를 요청한다") {
        val service = mockk<UndoService>()
        val expected = UndoExecution.Discarded(ENTRY, UndoOutcome.Irreversible(GitOperationKind.PUSH, "원격 반영"))
        coEvery { service.discardBlocked(ENTRY) } returns expected

        DiscardBlockedUndoEntryUseCase(service).execute(ENTRY) shouldBe expected

        coVerify(exactly = 1) { service.discardBlocked(ENTRY) }
        coVerify(exactly = 0) { service.undo(any()) }
    }
})
