package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.awt.event.KeyEvent

private val REFRESH = CommandId("graph.refresh")
private val COMMIT = CommandId("staging.commit")

/**
 * 저장된 단축키 오버라이드의 런타임 적용.
 *
 * 재지정 UI·충돌 해소·미등록 명령 오버라이드 처리 규칙은 UND-69 몫이고, 여기서는
 * **저장된 값이 실효 단축키가 되는 경로**만 다룬다.
 */
class ShortcutOverrideSpec : FunSpec({

    test("저장 표현과 단축키는 서로 손실 없이 옮겨진다") {
        val shortcut = Shortcut(Key.R, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.SHIFT))

        shortcut.toBinding().toShortcut() shouldBe shortcut
        shortcut.toBinding() shouldBe ShortcutBinding(
            keyCode = Key.R.nativeKeyCode,
            modifiers = setOf(ShortcutModifierKey.PRIMARY, ShortcutModifierKey.SHIFT),
        )
    }

    test("수식키 없는 단축키도 그대로 옮겨진다") {
        val shortcut = Shortcut(Key.F5)

        shortcut.toBinding().toShortcut() shouldBe shortcut
        ShortcutBinding(keyCode = KeyEvent.VK_F5).toShortcut() shouldBe shortcut
    }

    test("저장된 오버라이드는 재바인딩 후 실효 단축키가 된다") {
        val refresh = testCommand(REFRESH.value, shortcut = primaryShortcut(Key.R))
        val registry = registryOf(refresh)
        val overridden = Shortcut(Key.F5)

        registry.applyShortcutOverrides(mapOf(REFRESH to overridden)).shouldContainExactly(emptyList())

        registry.effectiveShortcutOf(refresh) shouldBe overridden
        registry.commandFor(overridden) shouldBe refresh
        // 기본 단축키는 더 이상 그 명령을 부르지 않는다.
        registry.commandFor(primaryShortcut(Key.R)) shouldBe null
        registry.shortcutLabelOf(refresh) shouldBe "F5"
    }

    test("오버라이드를 비우면 기본 단축키로 되돌아간다") {
        val refresh = testCommand(REFRESH.value, shortcut = primaryShortcut(Key.R))
        val registry = registryOf(refresh)
        registry.applyShortcutOverrides(mapOf(REFRESH to Shortcut(Key.F5)))

        registry.applyShortcutOverrides(emptyMap())

        registry.effectiveShortcutOf(refresh) shouldBe primaryShortcut(Key.R)
        registry.commandFor(primaryShortcut(Key.R)) shouldBe refresh
        registry.commandFor(Shortcut(Key.F5)) shouldBe null
    }

    test("기본 단축키가 없던 명령도 오버라이드로 단축키를 얻는다") {
        val commit = testCommand(COMMIT.value)
        val registry = registryOf(commit)

        registry.applyShortcutOverrides(mapOf(COMMIT to primaryShortcut(Key.Enter)))

        registry.commandFor(primaryShortcut(Key.Enter)) shouldBe commit
    }

    test("등록되지 않은 명령의 오버라이드는 적용하지 못한 것으로 돌려준다 — 저장된 값은 이 경로가 지우지 않는다") {
        val registry = registryOf(testCommand(REFRESH.value, shortcut = primaryShortcut(Key.R)))

        val rejected = registry.applyShortcutOverrides(mapOf(CommandId("not.registered") to Shortcut(Key.F5)))

        rejected shouldContainExactly listOf(CommandId("not.registered"))
        registry.effectiveShortcutOf(registry.commands.single()) shouldBe primaryShortcut(Key.R)
    }

    test("오버라이드는 다른 명령의 기본 단축키를 이기고 밀려난 쪽을 알린다") {
        val refresh = testCommand(REFRESH.value, shortcut = primaryShortcut(Key.R))
        val commit = testCommand(COMMIT.value, shortcut = primaryShortcut(Key.Enter))
        val registry = registryOf(refresh, commit)

        val rejected = registry.applyShortcutOverrides(mapOf(COMMIT to primaryShortcut(Key.R)))

        // 사용자가 지정한 키가 기본값에 밀리면 같은 설정 파일이 등록 순서에 따라 다른 결과를 낸다.
        rejected shouldContainExactly listOf(REFRESH)
        registry.commandFor(primaryShortcut(Key.R)) shouldBe commit
        registry.effectiveShortcutOf(refresh) shouldBe null
    }

    test("오버라이드끼리 겹치면 등록 순서가 앞선 명령이 이긴다") {
        val refresh = testCommand(REFRESH.value)
        val commit = testCommand(COMMIT.value)
        val registry = registryOf(refresh, commit)

        val rejected = registry.applyShortcutOverrides(
            mapOf(COMMIT to Shortcut(Key.F5), REFRESH to Shortcut(Key.F5)),
        )

        rejected shouldContainExactly listOf(COMMIT)
        registry.commandFor(Shortcut(Key.F5)) shouldBe refresh
        registry.effectiveShortcutOf(commit) shouldBe null
    }

    test("단축키 입력 처리기는 재바인딩된 실효 단축키를 따른다") {
        val executed = mutableListOf<CommandId>()
        val refresh = testCommand(REFRESH.value, shortcut = primaryShortcut(Key.R), action = { executed += REFRESH })
        val registry = registryOf(refresh)
        val center = CommandCenter(registry)

        registry.applyShortcutOverrides(mapOf(REFRESH to Shortcut(Key.F5)))
        center.shortcutHandler.handle(Shortcut(Key.F5))

        executed shouldContainExactly listOf(REFRESH)
        center.shortcutHandler.handle(primaryShortcut(Key.R)) shouldBe null
    }
})
