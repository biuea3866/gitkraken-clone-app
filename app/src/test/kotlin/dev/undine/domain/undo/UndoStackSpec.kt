package dev.undine.domain.undo

import dev.undine.domain.RefName
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant

private val MAIN = RefName("main")

private fun baseline(headSeed: Int) = RepositoryBaseline(branch = MAIN, head = commitId(headSeed))

private fun entry(headSeed: Int) = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(commitId(headSeed - 1)),
    baseline = baseline(headSeed),
    targetLabel = "커밋 $headSeed",
    recordedAt = Instant.ofEpochSecond(headSeed.toLong()),
)

/**
 * 세션 스택의 규칙 — LIFO, 상한, 오래된 항목부터 제거.
 *
 * 이력은 메모리에만 있다. 저장소에 무엇이 남는지는 `UndoRepositorySpec` 이 실제 저장소로 본다.
 */
class UndoStackSpec : FunSpec({

    test("가장 최근에 기록한 항목이 먼저 나온다") {
        val stack = UndoStack()
        stack.record(entry(1))
        stack.record(entry(2))

        stack.peek() shouldBe entry(2)
        stack.pop() shouldBe entry(2)
        stack.pop() shouldBe entry(1)
    }

    test("빈 스택은 조회도 제거도 없음을 돌려준다") {
        val stack = UndoStack()

        stack.peek() shouldBe null
        stack.pop() shouldBe null
        stack.size shouldBe 0
        stack.history().shouldBeEmpty()
    }

    test("상한을 넘으면 가장 오래된 항목부터 제거한다") {
        val stack = UndoStack(capacity = 3)
        (1..5).forEach { stack.record(entry(it)) }

        stack.size shouldBe 3
        stack.history() shouldContainExactly listOf(entry(5), entry(4), entry(3))
    }

    test("상한 기본값은 50 건이다") {
        UndoStack.DEFAULT_CAPACITY shouldBe 50

        val stack = UndoStack()
        (1..UndoStack.DEFAULT_CAPACITY + 2).forEach { stack.record(entry(it)) }

        stack.size shouldBe UndoStack.DEFAULT_CAPACITY
        stack.peek() shouldBe entry(UndoStack.DEFAULT_CAPACITY + 2)
        // 가장 오래된 두 건이 밀려났다.
        stack.history().last() shouldBe entry(3)
    }

    test("이력은 최신 우선 순서로 보여준다") {
        val stack = UndoStack()
        stack.record(entry(1))
        stack.record(entry(2))
        stack.record(entry(3))

        stack.history() shouldContainExactly listOf(entry(3), entry(2), entry(1))
    }

    test("상한이 0 이하면 스택을 만들 수 없다") {
        shouldThrow<IllegalArgumentException> { UndoStack(capacity = 0) }
    }

    test("새 스택은 이전 스택의 이력을 갖지 않는다 — 세션 단위다") {
        val previousSession = UndoStack()
        previousSession.record(entry(1))

        UndoStack().history().shouldBeEmpty()
    }
})
