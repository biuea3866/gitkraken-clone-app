package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.nativeKeyLocation
import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey

/**
 * 저장 표현([ShortcutBinding])과 화면 표현([Shortcut]) 사이의 변환.
 *
 * 두 타입이 따로 있는 이유는 레이어 방향이다 — 설정 파일이 담는 값은 domain 이 소유하고,
 * 키 입력 해석과 표기는 presentation 이 한다. 변환은 잃는 값 없이 왕복해야 하므로
 * 키 코드뿐 아니라 **키 위치**(숫자 키패드 등)까지 함께 옮긴다.
 */
fun Shortcut.toBinding(): ShortcutBinding = ShortcutBinding(
    keyCode = key.nativeKeyCode,
    modifiers = modifiers.map { it.toBindingModifier() }.toSet(),
    keyLocation = key.nativeKeyLocation,
)

fun ShortcutBinding.toShortcut(): Shortcut = Shortcut(
    key = Key(nativeKeyCode = keyCode, nativeKeyLocation = keyLocation),
    modifiers = modifiers.map { it.toShortcutModifier() }.toSet(),
)

/** 저장된 매핑을 레지스트리가 쓰는 형태로 옮긴다. 커맨드 id 는 저장 파일에서 문자열이다. */
fun Map<String, ShortcutBinding>.toShortcutOverrides(): Map<CommandId, Shortcut> =
    entries.associate { (commandId, binding) -> CommandId(commandId) to binding.toShortcut() }

private fun ShortcutModifier.toBindingModifier(): ShortcutModifierKey = when (this) {
    ShortcutModifier.PRIMARY -> ShortcutModifierKey.PRIMARY
    ShortcutModifier.SHIFT -> ShortcutModifierKey.SHIFT
    ShortcutModifier.ALT -> ShortcutModifierKey.ALT
}

private fun ShortcutModifierKey.toShortcutModifier(): ShortcutModifier = when (this) {
    ShortcutModifierKey.PRIMARY -> ShortcutModifier.PRIMARY
    ShortcutModifierKey.SHIFT -> ShortcutModifier.SHIFT
    ShortcutModifierKey.ALT -> ShortcutModifier.ALT
}
