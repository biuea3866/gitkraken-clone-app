@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.undine.presentation.graph

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draw.alpha
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropTarget

/** 플랫폼 payload에는 외부 데이터가 없다. 실제 조작 판단은 상태 홀더의 타입 값으로 한다. */
private object GraphDragTransferable : DragAndDropTransferable

internal fun Modifier.graphDragSource(
    state: GraphDragDropState?,
    source: () -> GraphDragSource,
): Modifier = if (state == null) {
    this
} else {
    dragAndDropSource {
        state.beginDrag(source())
        DragAndDropTransferData(
            transferable = GraphDragTransferable,
            supportedActions = listOf(DragAndDropTransferAction.Move),
        )
    }
}

/** 놓을 수 없는 대상의 불투명도. 색이 아니라 밝기만 낮춰 라이트/다크 양쪽에서 같이 흐려진다. */
private const val DROP_REFUSED_ALPHA = 0.38f

/**
 * 대상에 들어올 때 미리보기를 갱신하고, OS가 drop을 확정할 때만 확인 단계를 연다.
 *
 * 드래그 중 **놓을 수 없는 대상은 비활성으로 그린다** — 놓기 전에 보이지 않으면 사용자는 실행할 수
 * 없는 조합에 놓고 나서야 거부 문구를 본다 (AC1). 놓았을 때의 거부는 그대로 유지한다.
 */
@Composable
internal fun Modifier.graphDropTarget(
    state: GraphDragDropState?,
    target: () -> GraphDropTarget,
): Modifier {
    if (state == null) return this
    val graphTarget = target()
    val refused = state.refusalFor(graphTarget) != null
    val handler = remember(state, graphTarget) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                state.drop(graphTarget)
                return state.preview?.canDrop == true
            }

            override fun onEntered(event: DragAndDropEvent) {
                state.hover(graphTarget)
            }

            override fun onEnded(event: DragAndDropEvent) {
                state.endDrag()
            }
        }
    }
    return alpha(if (refused) DROP_REFUSED_ALPHA else 1f)
        .dragAndDropTarget(
            shouldStartDragAndDrop = { state.isDragging },
            target = handler,
        )
}
