package dev.undine.presentation.recovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.RecoveryOutcome
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

/**
 * 탐색 요약 — 현재 검사 대상과 남은 후보·예상 검사 횟수.
 *
 * 후보 수와 예상 횟수는 [BisectResult.Testing]이 실제로 계산해 준 값일 때만 담는다. 저장소에서
 * 복원한 세션은 그 수를 보유하지 않으므로 null이며, 화면은 지어낸 숫자 대신 "다음 판정에서 갱신됨"을
 * 알린다. 없는 값을 추정으로 채우면 사용자는 그것이 계산된 값인지 구분할 수 없다.
 */
data class BisectSummaryDisplay(
    val target: CommitId?,
    val remainingCandidates: Int?,
    val expectedRemainingChecks: Int?,
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

    /**
     * 복구는 적용됐지만 Undo 기록이 남지 않았을 때의 사유. null이면 기록까지 성공했다.
     *
     * [recoveryFailure]와 별개다 — 저장소는 이미 바뀌었으므로 "실패"로 접으면 사용자는 적용된 복구를
     * 없던 것으로 오해한다. 반대로 알리지 않으면 되돌릴 수 없게 된 사실을 모른 채 화면을 떠난다.
     */
    var recoveryUndoRecordFailure: UndineException? by mutableStateOf(null)
        private set

    /** 기존 ref 이동을 사용자가 경고를 보고 명시적으로 확인했는가. 확인 전에는 복구를 실행하지 않는다. */
    var refMoveConfirmed: Boolean by mutableStateOf(false)
        private set

    /** bisect 시작에 쓸 good 경계. reflog 목록에서 고른다. */
    var bisectGood: CommitId? by mutableStateOf(null)
        private set

    /** bisect 시작에 쓸 bad 경계. reflog 목록에서 고른다. */
    var bisectBad: CommitId? by mutableStateOf(null)
        private set
    var bisectSession: BisectSession? by mutableStateOf(null)
        private set
    var bisectResult: BisectResult? by mutableStateOf(null)
        private set
    var bisectFailure: UndineException? by mutableStateOf(null)
        private set

    /** bisect 세션 변경은 적용됐지만 Undo 기록이 남지 않았을 때의 사유. 판정 실패와 구분한다. */
    var bisectUndoRecordFailure: UndineException? by mutableStateOf(null)
        private set

    /** 마지막으로 시작한 미리보기 요청의 순번. 화면이 읽지 않으므로 상태로 두지 않는다. */
    private var previewRequestId: Int = 0

    /** 마지막으로 연 bisect 요청의 순번. 미리보기와 같은 이유로 상태가 아니다. */
    private var bisectRequestId: Int = 0

    val selectedPreview: ReflogCommitPreview?
        get() = (preview as? PreviewUiState.Loaded)?.preview

    val requiresRefMoveWarning: Boolean get() = recoveryMode == RecoveryMode.MoveExisting

    /** good·bad 경계가 모두 정해졌을 때만 시작할 수 있다. */
    val canStartBisect: Boolean get() = bisectGood != null && bisectBad != null

    /** reset은 세션이 없거나 실패해도 화면에서 항상 보이는 탈출 경로다. */
    val resetVisible: Boolean get() = true

    val bisectHistory: BisectHistoryDisplay
        get() = bisectSession?.let {
            BisectHistoryDisplay(it.good, it.bad, it.skipped)
        } ?: BisectHistoryDisplay(emptyList(), null, emptyList())

    /** 진행 중인 세션이나 마지막 걸음 결과가 있을 때의 탐색 요약. 복원 세션도 현재 대상을 보여 준다. */
    val bisectSummary: BisectSummaryDisplay?
        get() {
            val testing = bisectResult as? BisectResult.Testing
            val session = bisectSession
            if (testing == null && session == null) return null
            return BisectSummaryDisplay(
                target = testing?.commit ?: session?.testing,
                remainingCandidates = testing?.remainingCandidates,
                expectedRemainingChecks = testing?.expectedRemainingChecks,
            )
        }

    /** reflog와 저장된 bisect 세션만 읽는다. 느린 도달 불가 탐색은 여기서 절대 시작하지 않는다. */
    fun load(reflogLimit: Int = DEFAULT_REFLOG_LIMIT) {
        loadReflog(reflogLimit)
        val requestId = beginBisectRequest()
        // 저장소를 다시 읽는 순간 앞선 걸음의 결과는 현재 상태라는 근거를 잃는다. 세션만 갈아 끼우면
        // 이미 끝난 세션의 Testing 요약이 남아, 사용자는 존재하지 않는 대상에 판정을 붙인다.
        bisectResult = null
        scope.launch { restoreBisectSession(requestId) }
    }

    fun selectReflog(entry: ReflogEntry) {
        selectedEntry = entry
        preview = PreviewUiState.Loading
        val requestId = ++previewRequestId
        scope.launch {
            val loaded = try {
                PreviewUiState.Loaded(actions.loadPreview(entry.to))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                PreviewUiState.Failed(failure)
            }
            // 늦게 끝난 앞선 요청은 버린다. 그러지 않으면 B를 선택한 사용자가 A의 미리보기를 보고
            // B를 복구한다 — 되찾으러 온 사람이 잘못된 커밋을 되살리는 경로다.
            if (requestId == previewRequestId) preview = loaded
        }
    }

    fun selectRecoveryMode(mode: RecoveryMode) {
        recoveryMode = mode
        refMoveConfirmed = false
    }

    /**
     * 기존 ref 이동 메뉴를 연다. **여기서는 복구를 실행하지 않는다** — 경고를 먼저 보여 주고
     * [confirmRefMove]를 거쳐야 실행된다. 한 번의 클릭으로 ref 가 움직이면 되찾으러 온 사용자가
     * 새로 잃는다.
     */
    fun requestRefMove() = selectRecoveryMode(RecoveryMode.MoveExisting)

    /** 경고를 본 사용자가 이동을 명시적으로 확인했다. 확인 뒤에만 복구 호출이 허용된다. */
    fun confirmRefMove() {
        if (recoveryMode == RecoveryMode.MoveExisting) refMoveConfirmed = true
    }

    /** 확인을 취소하고 기본 경로(새 브랜치)로 되돌린다. */
    fun cancelRefMove() = selectRecoveryMode(RecoveryMode.NewBranch)

    fun recoverSelected(target: RecoveryTarget) {
        val entry = selectedEntry ?: return
        recoveryFailure = null
        recoveryUndoRecordFailure = null
        scope.launch {
            try {
                val outcome = actions.recover(entry.to, target)
                recoveredRef = outcome.value
                recoveryUndoRecordFailure = outcome.undoRecordFailure
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

    /** 선택한 reflog 항목의 커밋을 good 경계로 지정한다. */
    fun selectBisectGood(commit: CommitId) {
        bisectGood = commit
    }

    /** 선택한 reflog 항목의 커밋을 bad 경계로 지정한다. */
    fun selectBisectBad(commit: CommitId) {
        bisectBad = commit
    }

    /** 지정한 good·bad 경계로 탐색을 시작한다. 둘 중 하나라도 없으면 아무 것도 하지 않는다. */
    fun startSelectedBisect() {
        val good = bisectGood ?: return
        val bad = bisectBad ?: return
        startBisect(good, bad)
    }

    fun startBisect(good: CommitId, bad: CommitId) {
        val requestId = beginBisectRequest()
        scope.launch { runBisect(requestId) { actions.startBisect(good, bad) } }
    }

    fun markBisect(verdict: BisectVerdict) {
        val requestId = beginBisectRequest()
        scope.launch { runBisect(requestId) { actions.markBisect(verdict) } }
    }

    /** 키보드 경로도 마우스 버튼과 같은 상태 전이를 사용한다. */
    fun onKeyboardVerdict(verdict: BisectVerdict) = markBisect(verdict)

    fun resetBisect() {
        val requestId = beginBisectRequest()
        scope.launch {
            try {
                val outcome = actions.resetBisect()
                if (isCurrentBisectRequest(requestId)) {
                    bisectResult = null
                    bisectUndoRecordFailure = outcome.undoRecordFailure
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                if (isCurrentBisectRequest(requestId)) bisectFailure = failure
            }
            // 성공·실패와 무관하게 저장소를 다시 읽는다. 화면이 기억하는 세션이 저장소와 어긋나면
            // 사용자는 이미 끝난 세션에 판정을 붙이거나 남은 세션을 못 본 채 화면을 떠난다.
            restoreBisectSession(requestId)
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

    private suspend fun runBisect(requestId: Int, action: suspend () -> RecoveryOutcome<BisectResult>) {
        val outcome = try {
            action()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            if (isCurrentBisectRequest(requestId)) bisectFailure = failure
            null
        }
        if (isCurrentBisectRequest(requestId)) {
            bisectResult = outcome?.value
            bisectUndoRecordFailure = outcome?.undoRecordFailure
        }
        // 실패한 걸음도 저장소에 세션을 남길 수 있다. 재조회해야 현재 대상과 reset 경로가 맞는다.
        restoreBisectSession(requestId)
    }

    /**
     * 새 bisect 요청을 연다. 앞선 요청의 오류는 여기서 지운다.
     *
     * 늦게 끝난 앞선 요청은 이 번호로 걸러 낸다. 그러지 않으면 reset으로 정리한 뒤 되돌아온 옛 판정이
     * 세션 요약을 되살려, 사용자는 저장소에 없는 대상에 good/bad를 붙인다.
     */
    private fun beginBisectRequest(): Int {
        bisectFailure = null
        bisectUndoRecordFailure = null
        return ++bisectRequestId
    }

    private fun isCurrentBisectRequest(requestId: Int): Boolean = requestId == bisectRequestId

    /** 세션 복원 실패를 "세션 없음"으로 접지 않는다. reset은 여전히 화면에 남는다. */
    private suspend fun restoreBisectSession(requestId: Int) {
        // 재조회 전에 무효화한다. 실패했을 때 앞선 세션이 남으면 저장소에서 이미 끝난 세션의 판정
        // 버튼을 계속 보여 주게 된다. 실패는 bisectFailure로 드러나므로 "세션 없음"과 구분된다.
        if (!isCurrentBisectRequest(requestId)) return
        bisectSession = null
        try {
            val restored = actions.restoreBisect()
            if (isCurrentBisectRequest(requestId)) bisectSession = restored
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: UndineException) {
            // 앞선 판정 실패를 재조회 실패로 덮어쓰지 않는다 — 사용자가 먼저 알아야 할 것은 판정 실패다.
            if (isCurrentBisectRequest(requestId) && bisectFailure == null) bisectFailure = failure
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
