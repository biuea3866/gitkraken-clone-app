package dev.undine.presentation.recovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.ReflogCommitPreview
import dev.undine.application.reflog.ReflogListing
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.UnreachableCommitScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface ReflogUiState {
    data object Idle : ReflogUiState
    data object Loading : ReflogUiState
    data class Loaded(val entries: List<ReflogEntry>, val mayBeExpired: Boolean) : ReflogUiState
    data class Failed(val failure: UndineException) : ReflogUiState
}

sealed interface PreviewUiState {
    data object Idle : PreviewUiState
    data object Loading : PreviewUiState
    data class Loaded(val preview: ReflogCommitPreview) : PreviewUiState
    data class Failed(val failure: UndineException) : PreviewUiState
}

sealed interface UnreachableUiState {
    data object NotStarted : UnreachableUiState
    data object Scanning : UnreachableUiState
    data class Completed(val result: UnreachableCommitScan) : UnreachableUiState
    data class Failed(val failure: UndineException) : UnreachableUiState
}

enum class RecoveryMode { NewBranch, MoveExisting }

/**
 * BisectSession이 실제로 저장하는 누적 상태의 표시 모델.
 *
 * good·skipped의 순서와 현재 bad만 보유하므로, 이 값은 완전한 시간순 이벤트 로그가 아니다.
 */
data class BisectHistoryDisplay(
    val good: List<CommitId>,
    val currentBad: CommitId?,
    val skipped: List<CommitId>,
    val isChronologicalEventLog: Boolean = false,
)

@Stable
@Suppress("TooManyFunctions") // reflog·탐색·복구·bisect의 독립된 화면 전이를 한 상태 홀더가 소유한다.
class RecoveryState(
    private val actions: RecoveryActions,
    private val scope: CoroutineScope,
) {
    var reflog: ReflogUiState by mutableStateOf(ReflogUiState.Idle)
        private set
    var preview: PreviewUiState by mutableStateOf(PreviewUiState.Idle)
        private set
    var selectedEntry: ReflogEntry? by mutableStateOf(null)
        private set
    var unreachable: UnreachableUiState by mutableStateOf(UnreachableUiState.NotStarted)
        private set
    var recoveryMode: RecoveryMode by mutableStateOf(RecoveryMode.NewBranch)
        private set
    var recoveredRef: RefName? by mutableStateOf(null)
        private set
    var recoveryFailure: UndineException? by mutableStateOf(null)
        private set
    var bisectSession: BisectSession? by mutableStateOf(null)
        private set
    var bisectResult: BisectResult? by mutableStateOf(null)
        private set
    var bisectFailure: UndineException? by mutableStateOf(null)
        private set

    val selectedPreview: ReflogCommitPreview?
        get() = (preview as? PreviewUiState.Loaded)?.preview

    val requiresRefMoveWarning: Boolean get() = recoveryMode == RecoveryMode.MoveExisting

    /** reset은 세션이 없거나 실패해도 화면에서 항상 보이는 탈출 경로다. */
    val resetVisible: Boolean get() = true

    val bisectHistory: BisectHistoryDisplay
        get() = bisectSession?.let {
            BisectHistoryDisplay(it.good, it.bad, it.skipped)
        } ?: BisectHistoryDisplay(emptyList(), null, emptyList())

    /** reflog와 저장된 bisect 세션만 읽는다. 느린 도달 불가 탐색은 여기서 절대 시작하지 않는다. */
    fun load(reflogLimit: Int = DEFAULT_REFLOG_LIMIT) {
        loadReflog(reflogLimit)
        scope.launch { restoreBisectSession() }
    }

    fun selectReflog(entry: ReflogEntry) {
        selectedEntry = entry
        preview = PreviewUiState.Loading
        scope.launch {
            preview = try {
                PreviewUiState.Loaded(actions.loadPreview(entry.to))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                PreviewUiState.Failed(failure)
            }
        }
    }

    fun selectRecoveryMode(mode: RecoveryMode) {
        recoveryMode = mode
    }

    fun recoverSelected(target: RecoveryTarget) {
        val entry = selectedEntry ?: return
        recoveryFailure = null
        scope.launch {
            try {
                recoveredRef = actions.recover(entry.to, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                recoveryFailure = failure
            }
        }
    }

    /** 사용자 의도에 의해서만 느린 object database 전체 탐색을 시작한다. */
    fun startUnreachableScan(limit: Int = DEFAULT_UNREACHABLE_LIMIT) {
        if (unreachable is UnreachableUiState.Scanning) return
        unreachable = UnreachableUiState.Scanning
        scope.launch {
            unreachable = try {
                UnreachableUiState.Completed(actions.scanUnreachable(limit))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                UnreachableUiState.Failed(failure)
            }
        }
    }

    fun startBisect(good: CommitId, bad: CommitId) {
        scope.launch { runBisect { actions.startBisect(good, bad) } }
    }

    fun markBisect(verdict: BisectVerdict) {
        scope.launch { runBisect { actions.markBisect(verdict) } }
    }

    /** 키보드 경로도 마우스 버튼과 같은 상태 전이를 사용한다. */
    fun onKeyboardVerdict(verdict: BisectVerdict) = markBisect(verdict)

    fun resetBisect() {
        scope.launch {
            try {
                actions.resetBisect()
                bisectSession = null
                bisectResult = null
                bisectFailure = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                bisectFailure = failure
            }
        }
    }

    private fun loadReflog(limit: Int) {
        reflog = ReflogUiState.Loading
        scope.launch {
            reflog = try {
                actions.loadReflog(limit).toUiState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                ReflogUiState.Failed(failure)
            }
        }
    }

    private suspend fun runBisect(action: suspend () -> BisectResult) {
        bisectFailure = null
        bisectResult = try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            bisectFailure = failure
            null
        }
        if (bisectFailure == null) restoreBisectSession()
    }

    /** 세션 복원 실패를 "세션 없음"으로 접지 않는다. reset은 여전히 화면에 남는다. */
    private suspend fun restoreBisectSession() {
        try {
            bisectSession = actions.restoreBisect()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            bisectFailure = failure
        }
    }
}

private fun ReflogListing.toUiState(): ReflogUiState = ReflogUiState.Loaded(entries, mayBeExpired)

@Composable
fun rememberRecoveryState(actions: RecoveryActions): RecoveryState {
    val scope = rememberCoroutineScope()
    return remember(actions) { RecoveryState(actions, scope) }
}

private const val DEFAULT_REFLOG_LIMIT = 100
private const val DEFAULT_UNREACHABLE_LIMIT = 100
