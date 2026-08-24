package dev.undine.presentation.palette

import androidx.compose.ui.input.key.Key

/**
 * 팔레트 테스트가 공유하는 명령 생성기. 명령은 등록 티켓이 만드는 것이라 테스트는
 * 최소 필드만 채운 가짜 명령을 쓴다.
 */
internal fun testCommand(
    id: String,
    title: String = id,
    shortcut: Shortcut? = null,
    availability: () -> CommandAvailability = { CommandAvailability.Available },
    action: () -> Unit = {},
): Command = Command(
    id = CommandId(id),
    title = title,
    shortcut = shortcut,
    availability = availability,
    action = action,
)

/** `Cmd/Ctrl + <키>` 조합. 플랫폼 차이는 [ShortcutModifier.PRIMARY] 가 흡수한다. */
internal fun primaryShortcut(key: Key): Shortcut = Shortcut(key, setOf(ShortcutModifier.PRIMARY))

/** 테스트 기본 플랫폼은 [ShortcutPlatform.OTHER] 다 — 실행 OS 와 무관하게 결과가 같아야 한다. */
internal fun registryOf(
    vararg commands: Command,
    platform: ShortcutPlatform = ShortcutPlatform.OTHER,
): CommandRegistry = CommandRegistry(platform).apply { commands.forEach(::register) }

/** 팔레트 상태 홀더. 단축키 실행과 이력을 공유해야 하면 [session] 에 같은 인스턴스를 넘긴다. */
internal fun paletteStateOf(
    vararg commands: Command,
    platform: ShortcutPlatform = ShortcutPlatform.OTHER,
    session: CommandSession = CommandSession(),
): CommandPaletteState = CommandPaletteState(registryOf(*commands, platform = platform), session)

/** 단축키 처리기. 팔레트와 이력을 공유해야 하면 [session] 에 같은 인스턴스를 넘긴다. */
internal fun handlerOf(
    vararg commands: Command,
    platform: ShortcutPlatform = ShortcutPlatform.OTHER,
    session: CommandSession = CommandSession(),
): ShortcutHandler = ShortcutHandler(registryOf(*commands, platform = platform), session)
