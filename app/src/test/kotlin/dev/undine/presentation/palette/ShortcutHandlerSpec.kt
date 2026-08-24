package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val BLOCK_REASON = "저장소를 먼저 열어야 합니다"

/** 단축키 입력 → 명령 실행. 실행 조건을 만족하는 명령만 실행한다. */
class ShortcutHandlerSpec : FunSpec({

    test("등록된 단축키를 누르면 해당 명령이 실행된다") {
        var executed = 0
        val command = testCommand("branch.create", shortcut = primaryShortcut(Key.B), action = { executed++ })
        val handler = handlerOf(command)

        val outcome = handler.handle(primaryShortcut(Key.B))

        executed shouldBe 1
        outcome shouldBe CommandOutcome.Executed(CommandId("branch.create"))
    }

    test("실행 조건을 만족하지 않는 명령은 실행되지 않고 사유를 돌려준다") {
        var executed = 0
        val command = testCommand(
            id = "commit.create",
            shortcut = primaryShortcut(Key.Enter),
            availability = { CommandAvailability.Blocked(BLOCK_REASON) },
            action = { executed++ },
        )
        val handler = handlerOf(command)

        val outcome = handler.handle(primaryShortcut(Key.Enter))

        executed shouldBe 0
        outcome shouldBe CommandOutcome.Blocked(CommandId("commit.create"), BLOCK_REASON)
    }

    test("등록되지 않은 단축키는 아무 명령도 실행하지 않는다") {
        val handler = handlerOf(testCommand("branch.create", shortcut = primaryShortcut(Key.B)))

        handler.handle(primaryShortcut(Key.K)) shouldBe null
    }

    test("실행 중 발생한 오류는 삼키지 않고 실패 결과로 알린다") {
        val command = testCommand(
            id = "remote.fetch",
            shortcut = primaryShortcut(Key.F),
            action = { error("원격에 연결하지 못했습니다") },
        )
        val handler = handlerOf(command)

        val outcome = handler.handle(primaryShortcut(Key.F))

        outcome.shouldBeInstanceOf<CommandOutcome.Failed>()
        outcome.commandId shouldBe CommandId("remote.fetch")
        outcome.cause.message shouldBe "원격에 연결하지 못했습니다"
    }

    test("단축키로 실행한 명령도 최근 실행 이력에 최신순으로 쌓인다") {
        val session = CommandSession()
        val create = testCommand("branch.create", shortcut = primaryShortcut(Key.B))
        val delete = testCommand("branch.delete", shortcut = primaryShortcut(Key.D))
        val handler = handlerOf(create, delete, session = session)

        handler.handle(primaryShortcut(Key.B))
        handler.handle(primaryShortcut(Key.D))
        handler.handle(primaryShortcut(Key.B))

        session.recentCommandIds shouldContainExactly
            listOf(CommandId("branch.create"), CommandId("branch.delete"))
    }

    test("실행되지 않은 명령은 최근 실행 이력에 남지 않는다") {
        val session = CommandSession()
        val blocked = testCommand(
            id = "commit.create",
            shortcut = primaryShortcut(Key.Enter),
            availability = { CommandAvailability.Blocked(BLOCK_REASON) },
        )
        val failing = testCommand(
            id = "remote.fetch",
            shortcut = primaryShortcut(Key.F),
            action = { error("원격에 연결하지 못했습니다") },
        )
        val handler = handlerOf(blocked, failing, session = session)

        handler.handle(primaryShortcut(Key.Enter))
        handler.handle(primaryShortcut(Key.F))

        session.recentCommandIds shouldContainExactly emptyList()
    }
})
