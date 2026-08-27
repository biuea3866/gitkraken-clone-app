package dev.undine.infrastructure.settings

import dev.undine.domain.ShortcutBinding
import dev.undine.domain.ShortcutModifierKey
import java.awt.event.KeyEvent

/** 상위 객체를 적는 [encodeSettings] 와 읽는 쪽이 함께 쓴다 — 키 문자열을 두 곳에 적지 않는다. */
internal const val KEY_SHORTCUT_OVERRIDES = "shortcutOverrides"

private const val KEY_SHORTCUT_KEY_CODE = "keyCode"
private const val KEY_SHORTCUT_KEY_LOCATION = "keyLocation"
private const val KEY_SHORTCUT_MODIFIERS = "modifiers"

private const val EMPTY_JSON_OBJECT = "{}"

/**
 * 커맨드 id → 단축키 오버라이드 매핑의 JSON 표현.
 *
 * [SettingsPreferenceCodec] 과 같은 규칙을 따른다: 알 수 없는 키는 무시하고, 읽을 수 없는 값은
 * 오류가 아니다. 다만 **읽을 수 없는 항목은 그 항목만 버린다** — 수식키 하나를 못 읽었다고 남은
 * 부분으로 단축키를 만들면 사용자가 지정한 적 없는 키 조합에 명령이 묶인다. 항목이 없으면
 * 그 커맨드는 기본 단축키로 돌아갈 뿐이라 잃는 것이 더 적다.
 */
internal fun encodeShortcutOverrides(overrides: Map<String, ShortcutBinding>): String {
    if (overrides.isEmpty()) return EMPTY_JSON_OBJECT
    val entries = overrides.entries.joinToString(", ") { (commandId, binding) ->
        "${jsonString(commandId)}: ${encodeShortcutBinding(binding)}"
    }
    return "{ $entries }"
}

private fun encodeShortcutBinding(binding: ShortcutBinding): String {
    val modifiers = binding.modifiers.joinToString(", ") { jsonString(it.name) }
    return "{ \"$KEY_SHORTCUT_KEY_CODE\": ${binding.keyCode}, " +
        "\"$KEY_SHORTCUT_KEY_LOCATION\": ${binding.keyLocation}, " +
        "\"$KEY_SHORTCUT_MODIFIERS\": [$modifiers] }"
}

internal fun readShortcutOverrides(value: Any?): Map<String, ShortcutBinding> {
    val entries = value as? Map<*, *> ?: return DEFAULT_SETTINGS.shortcutOverrides
    return entries.entries
        .mapNotNull { (commandId, binding) ->
            val id = commandId as? String ?: return@mapNotNull null
            readShortcutBinding(binding)?.let { id to it }
        }
        .toMap()
}

/** 키 코드가 없거나 모르는 수식키가 섞여 있으면 **그 항목을 만들지 않는다**. */
private fun readShortcutBinding(value: Any?): ShortcutBinding? {
    val fields = value as? Map<*, *> ?: return null
    val keyCode = fields.readIntOrNull(KEY_SHORTCUT_KEY_CODE)
    val modifiers = readModifiers(fields[KEY_SHORTCUT_MODIFIERS])
    return if (keyCode == null || modifiers == null) {
        null
    } else {
        ShortcutBinding(
            keyCode = keyCode,
            modifiers = modifiers,
            keyLocation = fields.readIntOrNull(KEY_SHORTCUT_KEY_LOCATION) ?: KeyEvent.KEY_LOCATION_STANDARD,
        )
    }
}

/** 목록 자체가 없으면 수식키 없는 단축키다. 모르는 이름이 하나라도 있으면 해석을 포기한다. */
private fun readModifiers(value: Any?): Set<ShortcutModifierKey>? {
    val names = when (value) {
        null -> emptyList<Any?>()
        is List<*> -> value
        else -> return null
    }
    val parsed = names.map { name -> ShortcutModifierKey.entries.firstOrNull { it.name == name } }
    return if (parsed.any { it == null }) null else parsed.filterNotNull().toSet()
}

private fun Map<*, *>.readIntOrNull(key: String): Int? = (this[key] as? Long)
    ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
    ?.toInt()
