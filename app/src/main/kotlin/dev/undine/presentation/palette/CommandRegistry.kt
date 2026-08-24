package dev.undine.presentation.palette

/**
 * 팔레트와 단축키 처리기가 보는 **유일한 명령·단축키 조회원**.
 *
 * 레지스트리가 없으면 단축키 정의가 화면마다 흩어져 충돌을 발견할 수 없다. 그래서 충돌은
 * 런타임에 하나가 조용히 이기는 대신 **등록 시점에 실패**시킨다 — 개발 중에 드러나야 고칠 수 있다.
 *
 * 등록은 앱 시작 시 한 번 일어나는 것을 전제로 한다 (동시 등록을 방어하지 않는다).
 *
 * @param platform 수식키 표기·해석 기준. 여기서 흡수하므로 명령 등록자는 OS 를 몰라도 된다.
 */
class CommandRegistry(val platform: ShortcutPlatform = ShortcutPlatform.current()) {

    private val registered = LinkedHashMap<CommandId, Command>()
    private val shortcutOwners = LinkedHashMap<Shortcut, CommandId>()

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
            shortcutOwners[shortcut] = command.id
        }
        registered[command.id] = command
    }

    /** 단축키에 묶인 명령. 없으면 `null` 이다. */
    fun commandFor(shortcut: Shortcut): Command? = shortcutOwners[shortcut]?.let(registered::get)

    /** 이 레지스트리의 OS 표기로 그린 단축키. 단축키가 없는 명령은 `null` 이다. */
    fun shortcutLabelOf(command: Command): String? = command.shortcut?.displayOn(platform)
}
