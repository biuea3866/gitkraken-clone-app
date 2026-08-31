package dev.undine.presentation.preferences

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import dev.undine.presentation.palette.Command
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.Shortcut
import dev.undine.presentation.palette.toBinding
import dev.undine.presentation.palette.toShortcutOverrides

/**
 * 단축키 탭의 상태 홀더 — 재지정·충돌 해소·항목별 기본값 복원.
 *
 * **저장된 값만 그린다.** 행과 실효 단축키는 [synchronize] 가 [PreferencesState.settings] 를 읽어
 * 다시 만든다. 그 값은 저장이 성공했을 때만 바뀌므로, 재지정 요청은 저장을 통과한 뒤에야 화면에
 * 나타난다 — 실패했을 때 무엇으로 되돌릴지가 문제되지 않는다 (결정 G12 와 같은 방향).
 *
 * **삭제는 지금 등록된 명령의 오버라이드만 대상으로 한다.** 충돌 상대를 찾을 때
 * [CommandRegistry.commandFor] 를 쓰므로 미등록 명령은 애초에 후보가 아니고, 교체는 그 상대 하나의
 * 항목만 지운다. 같은 키를 가진 미등록 명령(조건부 등록·아직 배선 전)의 저장값은 그대로 남는다 —
 * 지우면 그 명령이 등록될 때 사용자가 지정했던 키가 이미 사라진 뒤다.
 *
 * **"단축키 없음" 은 만들지 않는다** (결정 G19). 되돌리기 경로는 [restoreDefault] 하나이며,
 * 저장 매핑에서 그 커맨드 id 항목만 지워 기본 단축키로 되돌린다. `SHORTCUT_OVERRIDES` 항목 복원은
 * 매핑 **전체**를 비우는 뜻이라 여기서 쓰지 않는다.
 */
@Stable
class ShortcutPreferencesController(
    private val preferences: PreferencesState,
    private val commands: CommandRegistry,
) {
    /**
     * 등록 순서대로의 명령 행, 그 뒤에 저장값만 있고 등록되지 않은 미적용 id 의 행.
     * 저장된 값이 바뀔 때만 갱신된다.
     */
    var rows: List<ShortcutPreferencesRow> by mutableStateOf(emptyList())
        private set

    /**
     * 저장돼 있지만 묶지 못한 커맨드 id. 등록되지 않았거나 다른 명령이 그 키를 먼저 잡은 것이다.
     * 지금 사용자가 할 일이 없는 정보라 대화상자를 띄우지 않고 탭 안 경고 한 줄로 요약한다 (결정 G20).
     */
    var unappliedCommandIds: List<CommandId> by mutableStateOf(emptyList())
        private set

    /** 확인을 기다리는 충돌. 있는 동안 저장된 오버라이드와 실효 단축키는 그대로다. */
    var conflict: ShortcutConflict? by mutableStateOf(null)
        private set

    /** 지금 키 입력을 기다리는 명령. `null` 이면 어떤 행도 입력을 잡고 있지 않다. */
    var capturingCommandId: CommandId? by mutableStateOf(null)
        private set

    /**
     * 저장된 오버라이드를 레지스트리에 얹고 행을 다시 만든다.
     *
     * 화면은 [PreferencesState.settings] 가 바뀔 때마다 이것을 부른다. 값이 바뀌는 시점은 저장·읽기가
     * **성공한** 뒤뿐이라, 이 경로가 곧 "저장된 값만 그린다" 는 규칙의 실행 지점이다.
     *
     * **묶지 못한 id 는 등록 여부와 무관하게 행을 얻는다** (결정 G20). 등록 명령 행 뒤에 미등록 id 의
     * 최소 행을 잇는다 — 경고 한 줄에만 남기면 어느 항목이 미적용인지 목록에서 확인할 수 없다.
     */
    fun synchronize() {
        val stored = preferences.settings.shortcutOverrides
        val unapplied = commands.applyShortcutOverrides(stored.toShortcutOverrides())
        unappliedCommandIds = unapplied
        val registered = commands.commands
        val registeredIds = registered.mapTo(mutableSetOf(), Command::id)
        rows = registered.map { command -> rowOf(command, stored.keys, unapplied) } +
            unapplied.filterNot(registeredIds::contains).map(::unregisteredRowOf)
    }

    /** 이 명령의 새 단축키를 받기 시작한다. 한 번에 한 행만 잡는다. */
    fun startCapture(commandId: CommandId) {
        capturingCommandId = commandId
    }

    fun cancelCapture() {
        capturingCommandId = null
    }

    /**
     * 캡처 중인 행이 받은 키 입력.
     *
     * @return 이 입력을 소비했는가. 수식키만 눌린 동안에는 조합이 아직 완성되지 않았으므로
     *   소비하지 않고 계속 기다린다.
     */
    fun capture(shortcut: Shortcut): Boolean {
        val commandId = capturingCommandId
        return when {
            commandId == null -> false
            shortcut.isModifierOnly() -> false
            shortcut.key == Key.Escape -> {
                cancelCapture()
                true
            }

            else -> {
                requestRebind(commandId, shortcut)
                true
            }
        }
    }

    /**
     * 이 명령을 [shortcut] 으로 재지정한다. 등록된 다른 명령이 이미 쓰고 있으면 저장하지 않고
     * [conflict] 로 알린다 — 교체는 사용자가 확인한 뒤에만 일어난다.
     */
    fun requestRebind(commandId: CommandId, shortcut: Shortcut) {
        capturingCommandId = null
        val owner = commands.commandFor(shortcut)?.takeIf { it.id != commandId }
        if (owner == null) {
            persist(commandId, shortcut, replaced = null)
        } else {
            conflict = ShortcutConflict(commandId, shortcut, owner.id, owner.title)
        }
    }

    /**
     * 교체를 확인한다. 대상이 그 키를 갖고 충돌 상대의 오버라이드는 지워져 기본값으로 돌아간다.
     *
     * 상대가 그 키를 **기본 단축키**로 쓰고 있었다면 지울 항목이 없다. 그 경우에도 대상의 소유가
     * 유지되는 것은 [CommandRegistry] 가 오버라이드를 기본값보다 먼저 묶기 때문이다 —
     * 등록 순서로 결과가 갈리지 않는다.
     */
    fun confirmReplace() {
        val pending = conflict ?: return
        conflict = null
        persist(pending.commandId, pending.requested, replaced = pending.ownerId)
    }

    /** 교체를 취소한다. 저장된 오버라이드도 실효 단축키도 건드리지 않는다. */
    fun cancelReplace() {
        conflict = null
    }

    /**
     * 이 명령만 기본 단축키로 되돌린다. 저장 매핑에서 그 커맨드 id 항목만 지우므로 다른 명령의
     * 오버라이드는 — 미등록 명령의 것까지 — 그대로 남는다.
     */
    fun restoreDefault(commandId: CommandId) {
        preferences.apply { settings ->
            settings.copy(shortcutOverrides = settings.shortcutOverrides - commandId.value)
        }
    }

    private fun persist(commandId: CommandId, shortcut: Shortcut, replaced: CommandId?) {
        val binding = shortcut.toBinding()
        preferences.apply { settings ->
            val kept = replaced?.let { settings.shortcutOverrides - it.value } ?: settings.shortcutOverrides
            settings.copy(shortcutOverrides = kept + (commandId.value to binding))
        }
    }

    private fun rowOf(
        command: Command,
        overriddenIds: Set<String>,
        unapplied: List<CommandId>,
    ): ShortcutPreferencesRow = ShortcutPreferencesRow(
        commandId = command.id,
        title = command.title,
        shortcutLabel = commands.shortcutLabelOf(command),
        isOverridden = command.id.value in overriddenIds,
        isUnapplied = command.id in unapplied,
        isRegistered = true,
    )
}

/**
 * 등록되지 않은 커맨드 id 의 행. 이름을 줄 [Command] 가 없어 id 를 그대로 쓴다.
 *
 * 저장값이 있어야만 [CommandRegistry.applyShortcutOverrides] 가 이 id 를 돌려주므로 출처는 늘
 * 사용자 오버라이드다. 동작 버튼은 붙지 않는다 — 배선 전 명령의 저장값을 여기서 지우면 그 명령이
 * 등록될 때 사용자가 지정했던 키가 이미 사라진 뒤다.
 */
private fun unregisteredRowOf(commandId: CommandId): ShortcutPreferencesRow = ShortcutPreferencesRow(
    commandId = commandId,
    title = commandId.value,
    shortcutLabel = null,
    isOverridden = true,
    isUnapplied = true,
    isRegistered = false,
)
