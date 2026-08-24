package dev.undine.presentation.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * 팔레트와 단축키를 **한 세션으로 묶어 소유**한다. 배선(UND-26)이 만지는 유일한 입구다.
 *
 * 왜 별도 타입인가. [CommandPaletteState] 와 [ShortcutHandler] 는 같은 [CommandSession] 을 받아야
 * "최근 실행이 앞선다" 가 입구(팔레트 클릭 / 단축키)에 상관없이 같게 적용된다. 그런데 둘을 따로
 * 생성하는 배선은 **각자 다른 세션을 넘겨도 컴파일된다** — 주석으로만 적힌 규약은 배선에서 깨진다.
 * 세션 생성을 여기로 감춰 두 구성요소가 다른 세션을 가질 **경로 자체를 없앤다**.
 *
 * 두 구성요소의 생성자는 `internal` 이고, 팔레트 패키지 밖에서 직접 만들지 않는다
 * (`CommandCenterSpec` 이 소스를 훑어 강제한다).
 */
@Stable
class CommandCenter(registry: CommandRegistry) {

    private val session = CommandSession()

    /** 팔레트 화면 상태 홀더. [shortcutHandler] 와 같은 세션을 공유한다. */
    val paletteState: CommandPaletteState = CommandPaletteState(registry, session)

    /** 단축키 처리기. [paletteState] 와 같은 세션을 공유한다. */
    val shortcutHandler: ShortcutHandler = ShortcutHandler(registry, session)
}

/** 컴포지션 수명 동안 유지되는 팔레트·단축키 묶음. 영속화 대상이 아니다. */
@Composable
fun rememberCommandCenter(registry: CommandRegistry): CommandCenter =
    remember(registry) { CommandCenter(registry) }
