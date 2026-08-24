package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** 명령·단축키의 유일한 조회원 — 등록 시점 충돌 감지가 핵심이다. */
class CommandRegistrySpec : FunSpec({

    test("등록한 명령을 등록 순서대로 돌려준다") {
        val open = testCommand("repository.open", "Open Repository")
        val branch = testCommand("branch.create", "Create Branch")

        registryOf(open, branch).commands shouldContainExactly listOf(open, branch)
    }

    test("같은 단축키를 가진 두 명령을 등록하면 두 번째 등록에서 실패한다") {
        val registry = registryOf(testCommand("branch.create", shortcut = primaryShortcut(Key.B)))

        val failure = shouldThrow<IllegalArgumentException> {
            registry.register(testCommand("branch.delete", shortcut = primaryShortcut(Key.B)))
        }

        failure.message.orEmpty() shouldContain "Ctrl+B"
        registry.commands.map { it.id } shouldContainExactly listOf(CommandId("branch.create"))
    }

    test("같은 id 를 두 번 등록하면 실패한다") {
        val registry = registryOf(testCommand("branch.create"))

        shouldThrow<IllegalArgumentException> { registry.register(testCommand("branch.create")) }
    }

    test("단축키가 없는 명령은 여럿 등록해도 충돌하지 않는다") {
        val registry = registryOf(testCommand("a"), testCommand("b"), testCommand("c"))

        registry.commands.size shouldBe 3
    }

    test("등록된 단축키로 명령을 찾고, 등록되지 않은 단축키는 찾지 못한다") {
        val branch = testCommand("branch.create", shortcut = primaryShortcut(Key.B))
        val registry = registryOf(branch)

        registry.commandFor(primaryShortcut(Key.B)) shouldBe branch
        registry.commandFor(primaryShortcut(Key.K)) shouldBe null
        registry.commandFor(Shortcut(Key.B)) shouldBe null
    }

    test("단축키 표기는 레지스트리의 플랫폼을 따른다") {
        val branch = testCommand("branch.create", shortcut = primaryShortcut(Key.B))

        registryOf(branch, platform = ShortcutPlatform.MACOS).shortcutLabelOf(branch) shouldBe "⌘B"
        registryOf(branch, platform = ShortcutPlatform.OTHER).shortcutLabelOf(branch) shouldBe "Ctrl+B"
        registryOf(testCommand("no.shortcut")).shortcutLabelOf(testCommand("no.shortcut")) shouldBe null
    }

    test("명령이 하나도 없는 레지스트리는 빈 목록을 준다") {
        registryOf().commands shouldContainExactly emptyList()
    }
})
