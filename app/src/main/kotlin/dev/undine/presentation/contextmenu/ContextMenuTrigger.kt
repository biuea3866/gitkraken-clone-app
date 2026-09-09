package dev.undine.presentation.contextmenu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * 우클릭·컨텍스트 메뉴 키로 메뉴를 여는 진입점.
 *
 * **AWT/Swing 이벤트를 직접 다루지 않는다** — Compose 포인터 입력으로 secondary 버튼을 판정하고,
 * 그 판정을 이 파일 하나에 모은다. 행마다 흩어 놓으면 플랫폼 차이가 행마다 다르게 샌다 (결정 D4).
 *
 * 판정은 [PointerEventPass.Initial] 에서 하고 변경을 **소비**한다. 그러지 않으면 같은 누름이 행의
 * `clickable` 까지 흘러가, 우클릭이 행 선택까지 함께 일으킨다.
 *
 * 키보드 경로도 함께 연다 — 메뉴가 마우스 전용이면 키보드만 쓰는 사용자는 그 조작에 닿을 수 없다
 * (compose-ui 규칙 8·결정 D7). 컨텍스트 메뉴 키와 Shift+F10 둘을 받는다.
 *
 * @param onOpen 메뉴를 열 **루트 기준** 좌표. 포인터 좌표는 이 요소 기준으로 오므로 요소의 루트
 *   위치를 더해 넘긴다 — 그러지 않으면 칩 안쪽 좌표가 화면 좌표로 쓰여 메뉴가 엉뚱한 데 뜬다.
 *   키보드로 열면 요소의 좌상단이다.
 */
@Composable
fun Modifier.contextMenuTrigger(onOpen: (Offset) -> Unit): Modifier {
    val latestOnOpen by rememberUpdatedState(onOpen)
    var nodeOrigin by remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { coordinates -> nodeOrigin = coordinates.positionInRoot() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                    val local = event.changes.firstOrNull()?.position ?: Offset.Zero
                    event.changes.forEach { change -> change.consume() }
                    latestOnOpen(nodeOrigin + local)
                }
            }
        }
        .onKeyEvent { event ->
            val opensMenu = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
            if (event.type == KeyEventType.KeyDown && opensMenu) {
                latestOnOpen(nodeOrigin)
                true
            } else {
                false
            }
        }
}
