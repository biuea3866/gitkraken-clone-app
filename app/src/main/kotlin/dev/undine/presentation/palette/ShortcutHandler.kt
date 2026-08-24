package dev.undine.presentation.palette

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * 단축키 입력을 명령 실행으로 잇는다. 조회원은 [CommandRegistry] 하나뿐이다.
 *
 * 실행 조건을 만족하지 않는 명령은 **실행하지 않고 사유를 담은 결과**를 돌려준다 —
 * 사용자에게 알리는 것은 호출부의 몫이다 (팔레트는 행 안 보조 텍스트, 그 밖에는 토스트).
 *
 * @param session 팔레트 상태 홀더와 **같은 인스턴스**여야 한다 — 단축키로 실행한 명령도
 *   같은 최근 실행 이력에 쌓여야 다음 팔레트 검색에서 앞선다. 그 규약은 [CommandCenter] 가
 *   소유하므로 팔레트 패키지 밖에서는 이 생성자를 쓰지 않고 [CommandCenter.shortcutHandler] 를 받는다.
 */
class ShortcutHandler internal constructor(
    private val registry: CommandRegistry,
    private val session: CommandSession,
) {

    /** 묶인 명령이 없으면 `null` — 호출부가 다른 처리기로 넘길 수 있게 구분한다. */
    fun handle(shortcut: Shortcut): CommandOutcome? = registry.commandFor(shortcut)?.let(session::execute)

    /** 키 입력을 레지스트리 플랫폼 기준으로 해석해 [handle] 로 넘긴다. */
    fun handleKeyEvent(event: KeyEvent): CommandOutcome? =
        shortcutOf(event, registry.platform)?.let(::handle)
}

/**
 * 포커스를 가진 하위 트리의 키 입력을 명령으로 처리한다. 텍스트 입력보다 먼저 보도록
 * preview 단계에서 가로채며, 묶인 명령이 있을 때만 이벤트를 소비한다.
 *
 * 앱 어디에 붙일지(전역 루트 vs 화면 단위)는 배선을 소유한 UND-26 이 정한다.
 */
fun Modifier.commandShortcuts(
    handler: ShortcutHandler,
    onOutcome: (CommandOutcome) -> Unit = {},
): Modifier = onPreviewKeyEvent { event ->
    val outcome = handler.handleKeyEvent(event)
    outcome?.also(onOutcome) != null
}
