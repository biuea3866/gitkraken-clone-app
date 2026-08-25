package dev.undine.presentation.undo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Undo 버튼과 실행 이력 패널의 상태 홀더 (compose-ui 규칙 1).
 *
 * UseCase 만 호출하고 `UndoService`·`UndoStack`·Gateway 는 알지 못한다 (레이어 규칙 3).
 *
 * **되돌리기는 한 번에 한 항목이다.** 실행 중에는 [isUndoing] 이 버튼을 잠가 같은 되돌리기가
 * 두 번 들어가지 못하게 하고, 끝나면 대상과 이력을 다시 읽어 화면을 맞춘다. 다른 Git 연산과의
 * 동시 저장소 접근은 `GitAccess` 가 이미 직렬화하므로 여기에 전역 busy 상태를 만들지 않는다
 * (wave 8 결정 A-N1).
 *
 * **거부는 성공이 아니다.** [UndoOutcome.Refused] 가 오면 결과를 그대로 남기고 대상 상태를 다시
 * 읽는다 — 되돌린 것처럼 표시하지 않는다.
 */
@Stable
class UndoState(
    private val scope: CoroutineScope,
    private val peekUndoTarget: PeekUndoTargetUseCase,
    private val loadUndoHistory: LoadUndoHistoryUseCase,
    private val undoLastOperation: UndoLastOperationUseCase,
) {
    /** 되돌릴 다음 한 항목의 상태. 없음·가능·막힘 셋 중 하나다. */
    var target: UndoTarget by mutableStateOf(UndoTarget.None)
        private set

    /** 최신 우선 세션 이력. 패널이 그대로 나열한다. */
    var history: List<OperationEntry> by mutableStateOf(emptyList())
        private set

    var isUndoing: Boolean by mutableStateOf(false)
        private set

    /** 마지막 되돌리기 결과. 아직 실행하지 않았으면 null 이다. */
    var lastOutcome: UndoOutcome? by mutableStateOf(null)
        private set

    /** 지금 Undo 버튼을 누를 수 있는가. 막힌 사유는 [target] 이 들고 있다. */
    val canUndo: Boolean get() = !isUndoing && target is UndoTarget.Undoable

    /** 대상과 이력을 다시 읽는다. 화면 진입과 다른 Git 연산 뒤에 배선이 호출한다. */
    fun refresh() {
        scope.launch { reload() }
    }

    /**
     * 스택 최상단 한 항목을 되돌린다. 버튼 클릭과 키보드 경로가 **같은 이 함수**로 들어온다.
     *
     * 재진입은 여기서 막는다 — [isUndoing] 을 코루틴 시작 **전에** 세워, 실행 중 도착한 두 번째
     * 클릭이 같은 항목을 또 소비하지 못하게 한다.
     */
    fun undo() {
        if (!canUndo) return
        isUndoing = true
        scope.launch {
            try {
                lastOutcome = undoLastOperation.execute()
                reload()
            } finally {
                isUndoing = false
            }
        }
    }

    /** Ctrl/Cmd+Z도 클릭과 같은 한 단계 되돌리기 경로를 쓴다. */
    fun undoFromKeyboard() = undo()

    /** 결과 안내를 닫는다. */
    fun dismissOutcome() {
        lastOutcome = null
    }

    private suspend fun reload() {
        target = peekUndoTarget.execute()
        history = loadUndoHistory.execute()
    }
}

/**
 * 컴포지션 수명에 묶인 Undo 상태.
 *
 * 첫 조합에서 한 번 읽어 둔다 — 스택이 이미 쌓인 채로 화면이 열릴 수 있다.
 */
@Composable
fun rememberUndoState(
    peekUndoTarget: PeekUndoTargetUseCase,
    loadUndoHistory: LoadUndoHistoryUseCase,
    undoLastOperation: UndoLastOperationUseCase,
): UndoState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) {
        UndoState(scope, peekUndoTarget, loadUndoHistory, undoLastOperation)
    }
    LaunchedEffect(state) { state.refresh() }
    return state
}
