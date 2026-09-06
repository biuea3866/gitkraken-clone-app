@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.undine.presentation.patch

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import java.awt.dnd.DropTargetDropEvent

/**
 * OS 파일 드롭을 받는 대상.
 *
 * **경로를 꺼내는 것은 [fileLocationsOf], 판정은 [patchDropOf] 가 한다** — 둘 다 modifier 밖의 함수라
 * 실제 OS 드래그 없이 "하나만 받고 여럿은 거부한다" 와 전송 실패 처리를 테스트할 수 있다. 이 파일에는
 * 네이티브 이벤트에서 전송 객체를 꺼내는 한 줄만 남는다.
 *
 * 앱 내부 드래그(`GraphDragDropModifier`)와는 별개 경로다 — 그쪽 payload 에는 외부 데이터가 없다.
 *
 * Compose 의 `dragData` 헬퍼는 `internal` 이라 쓸 수 없어 AWT `Transferable` 을 직접 읽는다.
 */
@Composable
internal fun Modifier.patchFileDropTarget(onDrop: (List<String>) -> Unit): Modifier {
    val handler = remember(onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                onDrop(fileLocationsOf((event.nativeEvent as? DropTargetDropEvent)?.transferable))
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = handler)
}
