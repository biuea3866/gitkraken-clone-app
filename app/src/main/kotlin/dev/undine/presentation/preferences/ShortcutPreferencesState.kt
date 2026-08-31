package dev.undine.presentation.preferences

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.Shortcut

/** 단축키 탭 요소를 가리키는 테스트 태그. 공통 태그([PreferencesTags])는 셸이 소유하므로 여기서 나눈다. */
object ShortcutPreferencesTags {
    const val LIST: String = "preferences.shortcuts.list"
    const val ROW: String = "preferences.shortcuts.row"
    const val REBIND: String = "preferences.shortcuts.rebind"
    const val CAPTURE: String = "preferences.shortcuts.capture"
    const val RESTORE_DEFAULT: String = "preferences.shortcuts.restoreDefault"
    const val CONFLICT: String = "preferences.shortcuts.conflict"
    const val CONFLICT_CONFIRM: String = "preferences.shortcuts.conflict.confirm"
    const val CONFLICT_CANCEL: String = "preferences.shortcuts.conflict.cancel"
    const val UNAPPLIED: String = "preferences.shortcuts.unapplied"
}

/**
 * 명령 한 건의 표시 상태 — 실효 단축키와 그 값이 기본값인지 사용자가 바꾼 값인지.
 *
 * **"단축키 없음" 상태를 담지 않는다** (결정 G19). [shortcutLabel] 이 `null` 인 것은 사용자가 해제한
 * 것이 아니라 **아직 어떤 키도 이 명령을 부르지 않는다**는 사실이다 — 기본 단축키가 없는 명령이거나,
 * 다른 명령이 같은 키를 먼저 잡아 [isUnapplied] 로 남은 경우다. 저장 계약은 `커맨드 id → 단축키` 이고
 * 값이 non-null 이라 해제라는 값 자체가 존재하지 않는다.
 *
 * @property isOverridden 저장된 오버라이드가 이 명령의 실효 단축키를 정하고 있는가.
 * @property isUnapplied 저장된 오버라이드나 기본 단축키를 다른 명령이 먼저 잡아 묶지 못했는가.
 * @property isRegistered 지금 레지스트리에 등록된 명령인가. 저장값만 있고 등록되지 않은 id 도
 *   미적용 사실을 자기 행에서 보여야 하므로(결정 G20) 행으로 오지만, 그 행에는 재지정·복원 같은
 *   동작이 붙지 않는다 — 배선 전 명령의 저장값을 이 화면이 건드리면 안 된다.
 */
@Immutable
data class ShortcutPreferencesRow(
    val commandId: CommandId,
    val title: String,
    val shortcutLabel: String?,
    val isOverridden: Boolean,
    val isUnapplied: Boolean,
    val isRegistered: Boolean,
)

/**
 * 재지정하려는 키를 **등록된 다른 명령**이 이미 쓰고 있다는 사실.
 *
 * 이 값이 있는 동안에는 저장된 오버라이드도 실효 단축키도 바뀌지 않는다 — 교체는 사용자가
 * 확인한 뒤에만 일어난다.
 *
 * @property ownerId 지금 [requested] 를 쓰고 있는 명령. 레지스트리에 **등록된** 명령만 온다 —
 *   미등록 명령의 저장 오버라이드는 충돌 판정 대상이 아니며 교체로 지워지지도 않는다.
 */
@Immutable
data class ShortcutConflict(
    val commandId: CommandId,
    val requested: Shortcut,
    val ownerId: CommandId,
    val ownerTitle: String,
)

/**
 * 행의 단축키 칸에 보일 문구.
 *
 * 묶인 키가 없으면 빈 문자열이다 — "해제됨" 같은 문구를 쓰면 [ShortcutPreferencesRow] 가 담지 않는
 * 상태를 화면이 만들어 낸다.
 */
fun ShortcutPreferencesRow.valueIn(texts: PreferencesStrings): String = when {
    isUnapplied -> texts.shortcutApplyFailed
    else -> shortcutLabel.orEmpty()
}

/** 값이 기본값에서 왔는지 사용자가 바꾼 값에서 왔는지. 공통 행의 출처 칸에 그대로 들어간다. */
fun ShortcutPreferencesRow.sourceLabelIn(texts: PreferencesStrings): String =
    if (isOverridden) texts.shortcutOverridden else texts.shortcutDefault

/**
 * 수식키만 눌린 입력인가. `Ctrl` 을 누르는 순간을 단축키로 잡으면 조합을 완성할 수 없다.
 *
 * 좌·우 키를 모두 본다 — 오른쪽 `Shift` 로 조합을 시작하는 사용자가 막히면 안 된다.
 */
internal fun Shortcut.isModifierOnly(): Boolean = key in MODIFIER_KEYS

private val MODIFIER_KEYS: Set<Key> = setOf(
    Key.CtrlLeft,
    Key.CtrlRight,
    Key.ShiftLeft,
    Key.ShiftRight,
    Key.AltLeft,
    Key.AltRight,
    Key.MetaLeft,
    Key.MetaRight,
)
