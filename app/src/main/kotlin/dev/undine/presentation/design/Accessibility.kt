package dev.undine.presentation.design

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * 포인터 입력의 최소 클릭 대상 (WCAG 2.5.8 Target Size (Minimum), 결정 G45-1).
 *
 * 44·48dp 는 **손가락**을 전제한 값이다. Undine 은 마우스·키보드로 쓰는 데스크톱 앱이고, 조밀한
 * 목록(커밋 이력·파일 목록)에 44dp 를 강제하면 한 화면에 담기는 행 수가 크게 줄어 정보 밀도를
 * 잃는다. **24dp 미만인 대상만** 이 값까지 키우고, 이미 24dp 이상인 것은 건드리지 않는다.
 */
val MinimumTargetSize = 24.dp

/**
 * 키보드 포커스가 이 요소에 있음을 **눈에 보이게** 한다.
 *
 * Tab 으로 이동할 수 있어도 지금 어디에 있는지 보이지 않으면 키보드만 쓰는 사용자는 길을 잃는다.
 * 포커스 링은 강조 토큰 색으로 그려 라이트·다크 양쪽에서 배경과 구분된다.
 *
 * 포커스 대상(`clickable`·`focusable`)을 **뒤에** 붙여야 그 대상의 포커스를 관찰한다 —
 * `Modifier.undineFocusRing().clickable { ... }` 순서로 쓴다.
 */
@Composable
fun Modifier.undineFocusRing(shape: Shape = RectangleShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) {
        Modifier.border(UndineTokens.shape.borderThick, UndineTokens.color.accent, shape)
    } else {
        Modifier
    }
    return this.then(ring).onFocusChanged { state -> focused = state.isFocused }
}

/**
 * 대화상자 표면 — 포커스를 가두고 ESC 로 닫는다.
 *
 * Undine 의 확인 대화상자 일부는 창(`Dialog`)이 아니라 **화면 안에 겹쳐 그리는 패널**이다. 그
 * 형태만으로는 두 가지가 성립하지 않는다: ① Tab 이 대화상자 밖으로 빠져나가 뒤에 가린 화면을
 * 조작하게 되고, ② ESC 로 닫을 수 없어 마우스로 취소 버튼을 찾아야 한다. 이 수정자가 둘을 함께
 * 메운다 — 창으로 바꾸면 겹쳐 그리는 배치(경고 색 테두리·본문 폭)를 화면마다 다시 정해야 한다.
 *
 * @param onDismiss ESC 가 눌렸을 때 부를 취소 경로. 취소는 어떤 변경도 실행하지 않아야 한다.
 */
@Composable
fun Modifier.undineDialogSurface(onDismiss: () -> Unit): Modifier {
    val requester = remember { FocusRequester() }
    // 열리는 순간 포커스를 안으로 가져온다 — 그러지 않으면 첫 Tab 이 뒤에 가린 화면으로 간다.
    LaunchedEffect(requester) { requester.requestFocus() }
    return this
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        }
        .focusRequester(requester)
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
}
