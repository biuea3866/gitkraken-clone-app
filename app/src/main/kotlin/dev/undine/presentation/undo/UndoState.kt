package dev.undine.presentation.undo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.undo.DiscardBlockedUndoEntryUseCase
import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.UndineException
import dev.undine.domain.undo.OperationEntry
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
 * **보여준 것만 실행한다.** 실행 요청에는 화면이 지금 표시 중인 기록을 그대로 실어 보낸다 —
 * 미리 보기와 실행 사이에 앱의 다른 연산이 새 기록을 남겼다면 UseCase 가 실행하지 않고
 * [UndoExecution.TargetChanged] 로 돌려보낸다 (wave 8 결정 G4).
 *
 * **성공이 아닌 것은 전부 남긴다.** 거부·대상 어긋남·실행 실패 셋 다 [lastExecution] 에 그대로
 * 남기고 **어느 경우든 다시 읽는다** — 실패한 화면이 낡은 대상을 들고 있으면 다음 클릭이 또
 * 엉뚱한 곳으로 간다.
 */
@Stable
class UndoState(
    private val scope: CoroutineScope,
    private val peekUndoTarget: PeekUndoTargetUseCase,
    private val loadUndoHistory: LoadUndoHistoryUseCase,
    private val undoLastOperation: UndoLastOperationUseCase,
    private val discardBlockedUndoEntry: DiscardBlockedUndoEntryUseCase,
) {
    /** 되돌릴 다음 한 항목의 상태. 없음·가능·막힘 셋 중 하나다. */
    var target: UndoTarget by mutableStateOf(UndoTarget.None)
        private set

    /** 최신 우선 세션 이력. 패널이 그대로 나열한다. */
    var history: List<OperationEntry> by mutableStateOf(emptyList())
        private set

    var isUndoing: Boolean by mutableStateOf(false)
        private set

    /** 마지막 실행 요청의 결말. 아직 아무것도 실행하지 않았으면 null 이다. */
    var lastExecution: UndoExecution? by mutableStateOf(null)
        private set

    /**
     * 대상·이력을 읽지 못한 마지막 실패. 읽기에 성공하면 지워진다.
     *
     * 읽기 실패를 삼키면 버튼이 이유 없이 잠긴 것처럼 보인다 — 왜 못 읽었는지를 말한다.
     */
    var loadFailure: UndineException? by mutableStateOf(null)
        private set

    /** 지금 Undo 버튼을 누를 수 있는가. 막힌 사유는 [target] 이 들고 있다. */
    val canUndo: Boolean get() = !isUndoing && target is UndoTarget.Undoable

    /**
     * 지금 막힌 최상단 기록을 지울 수 있는가.
     *
     * 막힌 항목을 지우는 경로가 없으면 그 아래의 되돌릴 수 있는 기록에 영영 닿지 못한다. 다만
     * **저장소 상태 때문에 막힌 기록은 지우지 않는다** — 그건 사용자가 해소하면 풀리는 사유라,
     * 지우면 되돌릴 수 있게 됐을 때 되돌릴 방법이 사라진다 ([discardableEntry]).
     */
    val canDiscardBlocked: Boolean get() = !isUndoing && discardableEntry != null

    /**
     * 지워도 되는 최상단 기록 — 기록 시점부터 복구 불가였던 것만이다.
     *
     * 판단 근거는 저장소가 아니라 기록 자체다. 화면이 내주는 버튼과 UseCase 가 실제로 지우는
     * 대상이 같은 기준을 써야, 눌렀는데 아무 일도 일어나지 않는 버튼이 생기지 않는다.
     */
    private val discardableEntry: OperationEntry?
        get() = (target as? UndoTarget.Blocked)?.entry?.takeIf { it.irreversibleReason != null }

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
        val expected = (target as? UndoTarget.Undoable)?.entry ?: return
        start(expected, undoLastOperation::execute)
    }

    /** Ctrl/Cmd+Z도 클릭과 같은 한 단계 되돌리기 경로를 쓴다. */
    fun undoFromKeyboard() = undo()

    /** 애초에 되돌릴 수 없는 기록으로 남은 최상단을 사용자가 확인하고 이력에서 지운다. */
    fun discardBlocked() {
        val expected = discardableEntry ?: return
        start(expected, discardBlockedUndoEntry::execute)
    }

    /** 결과 안내를 닫는다. */
    fun dismissOutcome() {
        lastExecution = null
    }

    private fun start(expected: OperationEntry, action: suspend (OperationEntry) -> UndoExecution) {
        if (isUndoing) return
        isUndoing = true
        scope.launch {
            try {
                lastExecution = runCatchingUndine(expected, action)
                reload()
            } finally {
                isUndoing = false
            }
        }
    }

    /**
     * 실행 실패를 **예외가 아니라 화면 상태**로 바꾼다.
     *
     * 예외가 그대로 빠져나가면 뒤따르는 재조회를 건너뛰어, 화면은 이미 소비된 기록을 계속
     * 되돌릴 수 있는 것처럼 보여준다. `UndineException` 만 잡으므로 취소는 전파된다
     * (예외 처리 규칙 5).
     */
    private suspend fun runCatchingUndine(
        expected: OperationEntry,
        action: suspend (OperationEntry) -> UndoExecution,
    ): UndoExecution = try {
        action(expected)
    } catch (failure: UndineException) {
        UndoExecution.Failed(expected, failure)
    }

    /**
     * 대상과 이력을 다시 읽는다.
     *
     * 읽기 실패는 **던지지 않고 상태로 남긴다.** 실행 실패 뒤 이어지는 재조회까지 실패하면 예외가
     * 코루틴 밖으로 새어 나가 아무 화면에도 닿지 않는다. 그때는 낡은 대상을 들고 있지 않도록
     * 대상을 비워, 잠긴 버튼과 사유가 함께 보이게 한다.
     *
     * 이력은 메모리 스택이라 실패하지 않으므로 먼저 읽는다 — 대상 조회가 실패해도 이력은 최신이다.
     */
    private suspend fun reload() {
        history = loadUndoHistory.execute()
        try {
            target = peekUndoTarget.execute()
            loadFailure = null
        } catch (failure: UndineException) {
            target = UndoTarget.None
            loadFailure = failure
        }
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
    discardBlockedUndoEntry: DiscardBlockedUndoEntryUseCase,
): UndoState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) {
        UndoState(scope, peekUndoTarget, loadUndoHistory, undoLastOperation, discardBlockedUndoEntry)
    }
    LaunchedEffect(state) { state.refresh() }
    return state
}
