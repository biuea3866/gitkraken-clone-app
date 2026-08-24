package dev.undine.presentation.palette

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as AwtKeyEvent

private const val MACOS_OS_NAME_PREFIX = "mac"
private const val MACOS_SEPARATOR = ""
private const val OTHER_SEPARATOR = "+"

/**
 * 플랫폼 중립 수식키. [PRIMARY] 가 macOS 의 `⌘` 와 그 외 OS 의 `Ctrl` 차이를 흡수한다 —
 * 명령을 등록하는 쪽은 OS 를 몰라도 된다.
 */
enum class ShortcutModifier { PRIMARY, SHIFT, ALT }

/** 수식키 표기와 키 입력 해석이 갈리는 축. macOS 인지 아닌지만 구분하면 충분하다. */
enum class ShortcutPlatform {
    MACOS,
    OTHER,
    ;

    companion object {

        /** `os.name` 문자열로 판정한다. 테스트가 값을 직접 넣을 수 있도록 분리했다. */
        fun of(osName: String): ShortcutPlatform =
            if (osName.lowercase().startsWith(MACOS_OS_NAME_PREFIX)) MACOS else OTHER

        fun current(): ShortcutPlatform = of(System.getProperty("os.name").orEmpty())
    }
}

/**
 * 키 하나와 수식키 조합. 등록·조회·표시의 공통 키다.
 *
 * OS 별 실제 수식키(`⌘` vs `Ctrl`)는 담지 않는다 — [ShortcutModifier.PRIMARY] 로 두고
 * 해석과 표기 시점에 [ShortcutPlatform] 이 결정한다.
 */
@Immutable
data class Shortcut(val key: Key, val modifiers: Set<ShortcutModifier> = emptySet())

/**
 * 팔레트 열기 기본 단축키 — `Cmd/Ctrl + K`.
 *
 * **이 티켓이 확정하는 유일한 기본 단축키다** (wave 3 결정 §UND-22). 개별 명령의 기본 단축키는
 * 명령을 등록하는 UND-26 이 정하며, 이 값을 실제 화면에 묶는 배선도 UND-26 소관이다.
 * 단축키 매핑 SSOT 문서 등재는 이 티켓 범위 밖이라 그 자리를 이 KDoc 이 대신한다.
 */
val OPEN_COMMAND_PALETTE_SHORTCUT: Shortcut = Shortcut(Key.K, setOf(ShortcutModifier.PRIMARY))

/**
 * 해당 OS 표기 문자열. macOS 는 기호를 붙여 쓰고(`⌘⇧P`), 그 외는 `+` 로 잇는다(`Ctrl+Shift+P`).
 *
 * 키 이름은 AWT 가 주는 이름을 그대로 쓴다 — 영문자·숫자는 글자 자체가 나오고,
 * 특수 키 이름은 JVM 로케일을 따를 수 있다.
 */
fun Shortcut.displayOn(platform: ShortcutPlatform): String {
    val modifierLabels = ShortcutModifier.entries
        .filter(modifiers::contains)
        .map { it.labelOn(platform) }
    return (modifierLabels + AwtKeyEvent.getKeyText(key.nativeKeyCode)).joinToString(platform.separator())
}

/**
 * 키 입력을 플랫폼 중립 [Shortcut] 으로 옮긴다. 눌린 순간(KeyDown)만 본다 —
 * 뗄 때까지 처리하면 한 번 누른 단축키가 두 번 실행된다.
 */
fun shortcutOf(event: KeyEvent, platform: ShortcutPlatform): Shortcut? {
    if (event.type != KeyEventType.KeyDown) return null

    val primaryPressed = when (platform) {
        ShortcutPlatform.MACOS -> event.isMetaPressed
        ShortcutPlatform.OTHER -> event.isCtrlPressed
    }
    val modifiers = buildSet {
        if (primaryPressed) add(ShortcutModifier.PRIMARY)
        if (event.isShiftPressed) add(ShortcutModifier.SHIFT)
        if (event.isAltPressed) add(ShortcutModifier.ALT)
    }
    return Shortcut(event.key, modifiers)
}

private fun ShortcutPlatform.separator(): String = when (this) {
    ShortcutPlatform.MACOS -> MACOS_SEPARATOR
    ShortcutPlatform.OTHER -> OTHER_SEPARATOR
}

private fun ShortcutModifier.labelOn(platform: ShortcutPlatform): String = when (platform) {
    ShortcutPlatform.MACOS -> when (this) {
        ShortcutModifier.PRIMARY -> "⌘"
        ShortcutModifier.SHIFT -> "⇧"
        ShortcutModifier.ALT -> "⌥"
    }

    ShortcutPlatform.OTHER -> when (this) {
        ShortcutModifier.PRIMARY -> "Ctrl"
        ShortcutModifier.SHIFT -> "Shift"
        ShortcutModifier.ALT -> "Alt"
    }
}
