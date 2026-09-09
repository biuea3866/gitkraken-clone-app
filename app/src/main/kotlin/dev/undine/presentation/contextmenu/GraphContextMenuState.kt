package dev.undine.presentation.contextmenu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.undine.domain.graphops.GraphOperation

/** 열려 있는 메뉴 하나 — 무엇을 지목했고, 커서가 어디였는가. */
@Immutable
data class GraphContextMenuRequest(
    val target: GraphContextTarget,
    val position: Offset,
)

/**
 * 그래프 우클릭 메뉴 상태 홀더.
 *
 * [selectedTarget] 은 메뉴가 닫힌 뒤에도 남는다 — **팔레트가 읽는 "지금 지목한 그래프 대상"** 이
 * 이 값이다. 팔레트는 이 대상을 [graphOperationsFor] 에 넣어 명령 가용성을 판정하므로, 브랜치를
 * 지목하면 `graph.merge`·`graph.rebase` 가 활성이 된다 (결정 D9-2·D10).
 */
@Stable
class GraphContextMenuState {

    var request: GraphContextMenuRequest? by mutableStateOf(null)
        private set

    var selectedTarget: GraphContextTarget? by mutableStateOf(null)
        private set

    /** 커서 위치에 메뉴를 연다. 지목한 대상은 메뉴를 닫은 뒤에도 팔레트가 읽는다. */
    fun open(target: GraphContextTarget, position: Offset) {
        selectedTarget = target
        request = GraphContextMenuRequest(target, position)
    }

    /** 메뉴를 열지 않고 대상만 지목한다 — 커밋 행을 왼쪽 클릭으로 골랐을 때가 이 경로다. */
    fun select(target: GraphContextTarget) {
        selectedTarget = target
    }

    /** 메뉴를 닫는다. **아무것도 실행하지 않는다** — ESC·메뉴 밖 클릭이 이 경로다. */
    fun close() {
        request = null
    }

    /**
     * 지목한 대상까지 버린다. 저장소가 바뀌면 이전 저장소의 ref·커밋을 가리키므로 남겨 두면
     * 팔레트가 **없는 참조를 대상으로** 조작을 내놓는다.
     */
    fun reset() {
        request = null
        selectedTarget = null
    }
}

/**
 * 메뉴가 실행 경로에 닿는 통로.
 *
 * @property selection 메뉴를 그리는 시점의 선택. 값이 아니라 통로인 이유는 메뉴가 열려 있는 동안
 *   선택이 바뀔 수 있기 때문이다.
 * @property onOperation 고른 조작을 넘길 곳. 기존 실행 경로
 *   ([dev.undine.presentation.graph.GraphOperationCallbacks])를 그대로 쓰므로 우클릭 실행이
 *   드래그&드롭과 같은 확인·Undo 기록 경로를 지난다.
 */
@Stable
class GraphContextMenuBinding(
    val state: GraphContextMenuState,
    val selection: () -> GraphContextSelection,
    val onOperation: (GraphOperation) -> Unit,
)

/** 앱 수명 동안 유지되는 메뉴 상태 — 팔레트 명령이 시작 시 한 번 등록되므로 홀더도 그 수명이다. */
@Composable
fun rememberGraphContextMenuState(): GraphContextMenuState = remember { GraphContextMenuState() }
