package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val BLOCK_REASON = "저장소를 먼저 열어야 합니다"

/** 팔레트 상태 홀더 — 검색어·열림 여부·세션 범위 최근 실행 순서. */
class CommandPaletteStateSpec : FunSpec({

    test("팔레트는 닫힌 상태로 시작하고 열고 닫을 수 있다") {
        val state = paletteStateOf(testCommand("branch.create"))

        state.isOpen shouldBe false
        state.open()
        state.isOpen shouldBe true
        state.close()
        state.isOpen shouldBe false
    }

    test("팔레트를 닫으면 검색어가 비워진다") {
        val state = paletteStateOf(testCommand("branch.create"))

        state.open()
        state.query = "bra"
        state.close()

        state.query shouldBe ""
    }

    test("명령을 실행하면 최근 목록 맨 앞에 오고 팔레트가 닫힌다") {
        var executed = 0
        val command = testCommand("branch.create", action = { executed++ })
        val state = paletteStateOf(command)
        state.open()

        val outcome = state.execute(command)

        executed shouldBe 1
        outcome shouldBe CommandOutcome.Executed(CommandId("branch.create"))
        state.recentCommandIds shouldContainExactly listOf(CommandId("branch.create"))
        state.isOpen shouldBe false
    }

    test("최근 실행 목록은 중복 없이 최신순으로 쌓인다") {
        val create = testCommand("branch.create")
        val delete = testCommand("branch.delete")
        val state = paletteStateOf(create, delete)

        state.execute(create)
        state.execute(delete)
        state.execute(create)

        state.recentCommandIds shouldContainExactly listOf(CommandId("branch.create"), CommandId("branch.delete"))
    }

    test("실행 조건 불충족 명령은 실행되지 않고 팔레트가 열린 채로 남는다") {
        var executed = 0
        val command = testCommand(
            id = "commit.create",
            availability = { CommandAvailability.Blocked(BLOCK_REASON) },
            action = { executed++ },
        )
        val state = paletteStateOf(command)
        state.open()

        val outcome = state.execute(command)

        executed shouldBe 0
        outcome shouldBe CommandOutcome.Blocked(CommandId("commit.create"), BLOCK_REASON)
        state.isOpen shouldBe true
        state.recentCommandIds shouldContainExactly emptyList()
    }

    test("실행 중 오류가 나면 팔레트를 닫고 실패를 알린다") {
        val command = testCommand("remote.fetch", action = { error("원격에 연결하지 못했습니다") })
        val state = paletteStateOf(command)
        state.open()

        val outcome = state.execute(command)

        outcome.shouldBeInstanceOf<CommandOutcome.Failed>()
        state.isOpen shouldBe false
        state.recentCommandIds shouldContainExactly emptyList()
    }

    test("후보는 실행 가능 여부와 플랫폼 표기 단축키를 함께 준다") {
        val create = testCommand("branch.create", "Create Branch", shortcut = primaryShortcut(Key.B))
        val commit = testCommand(
            id = "commit.create",
            title = "Commit Changes",
            availability = { CommandAvailability.Blocked(BLOCK_REASON) },
        )
        val state = paletteStateOf(create, commit, platform = ShortcutPlatform.MACOS)

        val candidates = state.candidates

        candidates.map { it.command.id } shouldContainExactly
            listOf(CommandId("branch.create"), CommandId("commit.create"))
        candidates[0].availability shouldBe CommandAvailability.Available
        candidates[0].shortcutLabel shouldBe "⌘B"
        candidates[1].availability shouldBe CommandAvailability.Blocked(BLOCK_REASON)
        candidates[1].shortcutLabel shouldBe null
    }

    test("검색어를 넣으면 후보가 좁혀지고 최근 실행이 앞선다") {
        val create = testCommand("branch.create", "Create Branch")
        val delete = testCommand("branch.delete", "Delete Branch")
        val state = paletteStateOf(create, delete)

        state.execute(delete)
        state.query = "branch"

        state.candidates.map { it.command.id } shouldContainExactly
            listOf(CommandId("branch.delete"), CommandId("branch.create"))
    }

    test("단축키로 실행한 명령도 팔레트 검색에서 앞선다") {
        val session = CommandSession()
        val create = testCommand("branch.create", "Create Branch")
        val delete = testCommand("branch.delete", "Delete Branch", shortcut = primaryShortcut(Key.D))
        val registry = registryOf(create, delete)
        val state = CommandPaletteState(registry, session)

        ShortcutHandler(registry, session).handle(primaryShortcut(Key.D))
        state.query = "branch"

        state.recentCommandIds shouldContainExactly listOf(CommandId("branch.delete"))
        state.candidates.map { it.command.id } shouldContainExactly
            listOf(CommandId("branch.delete"), CommandId("branch.create"))
    }

    test("등록된 명령이 없으면 후보도 비었음을 알린다") {
        val state = paletteStateOf()

        state.hasCommands shouldBe false
        state.candidates shouldContainExactly emptyList()
    }
})
