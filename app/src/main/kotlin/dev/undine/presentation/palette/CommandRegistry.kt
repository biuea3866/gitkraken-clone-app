package dev.undine.presentation.palette

/**
 * 팔레트와 단축키 처리기가 보는 **유일한 명령·단축키 조회원**.
 *
 * 레지스트리가 없으면 단축키 정의가 화면마다 흩어져 충돌을 발견할 수 없다. 그래서 충돌은
 * 런타임에 하나가 조용히 이기는 대신 **등록 시점에 실패**시킨다 — 개발 중에 드러나야 고칠 수 있다.
 *
 * 등록은 앱 시작 시 한 번 일어나는 것을 전제로 한다 (동시 등록을 방어하지 않는다).
 *
 * **사용자가 바꾼 단축키는 [applyShortcutOverrides] 로 얹는다.** [Command.shortcut] 은 명령이
 * 들고 오는 **기본 단축키**이고 불변이라, 실효 단축키는 여기서 기본값과 오버라이드를 합쳐 정한다.
 * 등록 시점 충돌과 달리 오버라이드 충돌은 **설정 파일에서 오는 값**이라 예외로 앱을 멈추지 않고
 * 적용하지 못한 커맨드를 돌려준다 — 재지정 UI 와 충돌 해소는 단축키 탭이 소유한다.
 *
 * @param platform 수식키 표기·해석 기준. 여기서 흡수하므로 명령 등록자는 OS 를 몰라도 된다.
 */
class CommandRegistry(val platform: ShortcutPlatform = ShortcutPlatform.current()) {

    private val registered = LinkedHashMap<CommandId, Command>()
    private val overrides = LinkedHashMap<CommandId, Shortcut>()
    private val shortcutOwners = LinkedHashMap<Shortcut, CommandId>()
    private val effectiveShortcuts = LinkedHashMap<CommandId, Shortcut>()

    /** 등록 순서를 유지한 명령 목록. 검색 결과의 동점 순서가 여기서 정해진다. */
    val commands: List<Command> get() = registered.values.toList()

    /** 이미 쓰인 id·단축키면 [IllegalArgumentException] 으로 등록을 거부한다. */
    fun register(command: Command) {
        require(!registered.containsKey(command.id)) { "이미 등록된 명령 id 입니다: ${command.id}" }

        val shortcut = command.shortcut
        if (shortcut != null) {
            val owner = shortcutOwners[shortcut]
            require(owner == null) {
                "단축키 ${shortcut.displayOn(platform)} 는 이미 $owner 가 쓰고 있습니다: ${command.id}"
            }
        }
        registered[command.id] = command
        rebind()
    }

    /**
     * 저장된 단축키 오버라이드를 지금 등록된 명령에 적용한다. **넘긴 매핑이 전부**다 —
     * 목록에서 빠진 커맨드는 기본 단축키로 돌아간다.
     *
     * 겹치는 단축키는 **오버라이드가 기본값을 이긴다**. 사용자가 지정한 키가 명령이 들고 온 기본값에
     * 밀리면, 같은 설정 파일이 등록 순서에 따라 다른 결과를 내고 재지정이 재시작 후 뒤집힌다.
     * 오버라이드끼리·기본값끼리 겹칠 때만 등록 순서가 앞선 명령이 이긴다. 진 쪽은 묶인 단축키 없이
     * 남고 (기본값으로 조용히 되돌리면 사용자가 지정하지 않은 키에 다시 묶인다) 결과에 담겨 돌아온다.
     *
     * @return 적용하지 못한 커맨드 id — 등록돼 있지 않거나 다른 실효 단축키와 겹친 것.
     *   저장된 매핑을 이 경로가 지우지는 않는다.
     */
    fun applyShortcutOverrides(requested: Map<CommandId, Shortcut>): List<CommandId> {
        overrides.clear()
        overrides.putAll(requested)
        val unregistered = requested.keys.filterNot(registered::containsKey)
        return unregistered + rebind()
    }

    /** 지금 이 명령을 부르는 단축키. 오버라이드가 있으면 그것이, 없으면 기본값이 실효값이다. */
    fun effectiveShortcutOf(command: Command): Shortcut? = effectiveShortcuts[command.id]

    /** 단축키에 묶인 명령. 없으면 `null` 이다. */
    fun commandFor(shortcut: Shortcut): Command? = shortcutOwners[shortcut]?.let(registered::get)

    /** 이 레지스트리의 OS 표기로 그린 **실효** 단축키. 묶인 단축키가 없으면 `null` 이다. */
    fun shortcutLabelOf(command: Command): String? = effectiveShortcutOf(command)?.displayOn(platform)

    /**
     * 실효 단축키 표를 처음부터 다시 만든다.
     *
     * 증분 갱신하지 않는 이유: 오버라이드가 풀리면 기본 단축키가 되살아나야 하고, 그 되살아난
     * 값이 또 다른 오버라이드와 겹칠 수 있다. 부분 갱신으로는 그 연쇄를 일관되게 풀 수 없다.
     *
     * **오버라이드를 가진 명령부터 묶는다.** 사용자가 지정한 키는 명령이 들고 온 기본값보다 뒤에
     * 놓이면 안 된다 — 그러면 재지정 결과가 등록 순서에 따라 달라진다.
     *
     * @return 겹쳐서 묶지 못한 커맨드 id.
     */
    private fun rebind(): List<CommandId> {
        shortcutOwners.clear()
        effectiveShortcuts.clear()
        val (overridden, byDefault) = registered.values.partition { overrides.containsKey(it.id) }
        return (overridden + byDefault).mapNotNull(::claim)
    }

    /** 실효 단축키를 잡는다. 이미 다른 명령이 쓰고 있으면 묶지 못한 커맨드 id 를 돌려준다. */
    private fun claim(command: Command): CommandId? {
        val shortcut = overrides[command.id] ?: command.shortcut
        return when {
            shortcut == null -> null
            shortcutOwners.containsKey(shortcut) -> command.id
            else -> {
                shortcutOwners[shortcut] = command.id
                effectiveShortcuts[command.id] = shortcut
                null
            }
        }
    }
}
