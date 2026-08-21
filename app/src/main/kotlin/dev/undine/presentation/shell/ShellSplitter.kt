package dev.undine.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.focusable
import dev.undine.presentation.design.UndineTokens

/**
 * 두 영역 사이의 크기 조절 손잡이.
 *
 * **드래그와 키보드가 같은 [onResize] 로 모인다** — 마우스 전용 동작을 만들지 않는다
 * (compose-ui 규칙 8, 티켓 공통 규약 4). 포커스를 받은 뒤 방향키를
 * [ShellSplitDefaults.KEYBOARD_STEP] 단위로 누르면 드래그와 같은 경로로 비율이 바뀐다.
 *
 * @param onResize 분할선이 움직인 거리. **오른쪽·아래가 양수**다. 최소 크기 판정은 호출부의
 *   [ShellSplitState] 가 하며 이 컴포넌트는 조작 거리만 전달한다.
 * @param label 접근성 설명. 문자열은 `strings.shell.*` 에서 온다.
 */
@Composable
fun ShellSplitter(
    orientation: ShellSplitterOrientation,
    label: String,
    testTag: String,
    onResize: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentOnResize by rememberUpdatedState(onResize)
    // 그리는 두께와 최소 크기 판정이 같은 출처를 봐야 남은 영역이 최소 크기 아래로 밀리지 않는다.
    val thickness = ShellSplitDefaults.SPLITTER_THICKNESS
    val vertical = orientation == ShellSplitterOrientation.VERTICAL
    val dragState = rememberDraggableState { deltaPixels ->
        currentOnResize(with(density) { deltaPixels.toDp() })
    }

    Box(
        modifier = modifier
            .then(
                if (vertical) {
                    Modifier.width(thickness).fillMaxHeight()
                } else {
                    Modifier.height(thickness).fillMaxWidth()
                },
            )
            .background(UndineTokens.color.border)
            .draggable(
                state = dragState,
                orientation = if (vertical) Orientation.Horizontal else Orientation.Vertical,
            )
            .onKeyEvent { event ->
                val step = keyboardStepFor(event.type, event.key, vertical)
                if (step == null) false else true.also { currentOnResize(step) }
            }
            .focusable()
            .semantics { contentDescription = label }
            .testTag(testTag),
    )
}

/**
 * 방향키 한 번이 옮길 거리. 관심 없는 키와 키 뗌(up)은 `null` 로 돌려 이벤트를 소비하지 않는다 —
 * 삼키면 상위의 포커스 이동 같은 기본 동작이 사라진다.
 */
private fun keyboardStepFor(type: KeyEventType, key: Key, vertical: Boolean): Dp? {
    if (type != KeyEventType.KeyDown) return null
    val decrease = if (vertical) Key.DirectionLeft else Key.DirectionUp
    val increase = if (vertical) Key.DirectionRight else Key.DirectionDown
    return when (key) {
        decrease -> -ShellSplitDefaults.KEYBOARD_STEP
        increase -> ShellSplitDefaults.KEYBOARD_STEP
        else -> null
    }
}
