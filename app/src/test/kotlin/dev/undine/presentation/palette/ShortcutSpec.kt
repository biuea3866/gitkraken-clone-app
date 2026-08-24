package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 단축키 모델 — 플랫폼별 수식키 흡수와 표기. */
class ShortcutSpec : FunSpec({

    test("PRIMARY 수식키는 macOS 에서 ⌘, 그 외 OS 에서 Ctrl 로 표기된다") {
        val shortcut = Shortcut(Key.K, setOf(ShortcutModifier.PRIMARY))

        shortcut.displayOn(ShortcutPlatform.MACOS) shouldBe "⌘K"
        shortcut.displayOn(ShortcutPlatform.OTHER) shouldBe "Ctrl+K"
    }

    test("수식키가 여러 개면 PRIMARY·SHIFT·ALT 순서로 표기된다") {
        val shortcut = Shortcut(Key.P, setOf(ShortcutModifier.ALT, ShortcutModifier.SHIFT, ShortcutModifier.PRIMARY))

        shortcut.displayOn(ShortcutPlatform.MACOS) shouldBe "⌘⇧⌥P"
        shortcut.displayOn(ShortcutPlatform.OTHER) shouldBe "Ctrl+Shift+Alt+P"
    }

    test("수식키가 없으면 키 이름만 표기된다") {
        Shortcut(Key.K).displayOn(ShortcutPlatform.OTHER) shouldBe "K"
    }

    test("os.name 으로 플랫폼을 판정한다") {
        ShortcutPlatform.of("Mac OS X") shouldBe ShortcutPlatform.MACOS
        ShortcutPlatform.of("Windows 11") shouldBe ShortcutPlatform.OTHER
        ShortcutPlatform.of("Linux") shouldBe ShortcutPlatform.OTHER
        ShortcutPlatform.of("") shouldBe ShortcutPlatform.OTHER
    }

    test("팔레트 열기 기본 단축키는 PRIMARY + K 다") {
        OPEN_COMMAND_PALETTE_SHORTCUT shouldBe Shortcut(Key.K, setOf(ShortcutModifier.PRIMARY))
        OPEN_COMMAND_PALETTE_SHORTCUT.displayOn(ShortcutPlatform.MACOS) shouldBe "⌘K"
        OPEN_COMMAND_PALETTE_SHORTCUT.displayOn(ShortcutPlatform.OTHER) shouldBe "Ctrl+K"
    }
})
