package dev.undine.presentation.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.diagnostics.DiagnosticsUseCases
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.LogDirectoryMissing
import dev.undine.domain.diagnostics.OpenLogDirectoryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 고급 탭의 로그 디렉터리 상태 홀더 (compose-ui 규칙 1).
 * [DiagnosticsUseCases] 만 호출하고 Gateway 를 알지 못한다.
 *
 * **'아직 없음' 은 실패가 아니다.** 아무 문제도 없었거나 사용자가 지운 것이므로 사유를 띄우지 않고
 * 열기만 비활성으로 둔다 — 사용자가 고칠 것이 없는 오류를 보여 주지 않는다.
 *
 * **열지 못한 것은 조용한 성공이 되지 않는다.** 아무 창도 열리지 않았는데 성공으로 보이면 사용자는
 * 무엇이 잘못됐는지 알 길이 없다 — 사유를 [openFailureReason] 으로 그대로 내보낸다.
 */
@Stable
class LogDirectoryState(
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticsUseCases,
) {
    /** 마지막 조회 결과. 조회는 경로를 만들지 않으므로 '아직 없음' 을 다시 관측할 수 있다. */
    var location: LogDirectoryLocation by mutableStateOf(LogDirectoryMissing)
        private set

    /** 파일 관리자를 띄우지 못한 사유. `null` 이면 실패한 적이 없거나 마지막 열기가 성공했다. */
    var openFailureReason: String? by mutableStateOf(null)
        private set

    /** 이 홀더가 마지막으로 줄 세운 작업. 조회와 열기가 시작한 순서대로 실행된다. */
    private var lastWork: Job = Job().apply { complete() }

    /** 화면에 보일 경로. 디렉터리가 없으면 `null` 이고 화면이 '아직 없음' 문구를 쓴다. */
    val path: String?
        get() = (location as? LogDirectoryLocation.Found)?.path?.toString()

    /** 폴더 열기를 내줄 수 있는가. 없는 디렉터리를 열라고 누르게 두지 않는다. */
    val canOpen: Boolean get() = location is LogDirectoryLocation.Found

    /** 로그 디렉터리 위치를 다시 읽는다. 탭 진입 시 배선이 호출한다. */
    fun refresh() {
        enqueue { location = diagnostics.locateLogDirectory.execute() }
    }

    /**
     * 로그 디렉터리를 파일 관리자로 연다.
     *
     * 디렉터리가 없으면 **UseCase 를 부르지 않는다** — 화면이 이미 없다는 것을 알고 있고, 부를수록
     * 아무 일도 일어나지 않는 호출만 늘어난다. 열기 사이에 디렉터리가 사라졌으면 그 사실이
     * [LogDirectoryMissing] 으로 돌아오므로 표시를 그 결과에 맞춘다.
     */
    fun open() {
        if (!canOpen) return
        enqueue {
            when (val result = diagnostics.openLogDirectory.execute()) {
                OpenLogDirectoryResult.Opened -> openFailureReason = null
                is OpenLogDirectoryResult.OpenFailed -> openFailureReason = result.reason
                LogDirectoryMissing -> {
                    // 조회와 열기 사이에 사라졌다 — 실패가 아니라 상태가 바뀐 것이다.
                    location = LogDirectoryMissing
                    openFailureReason = null
                }
            }
        }
    }

    /**
     * 작업을 앞선 작업 뒤에 줄 세운다. 호출한 순서가 곧 조회·열기 순서다 — 요청마다 코루틴을 따로
     * 띄우면 늦게 끝난 조회가 열기 결과를 낡은 값으로 덮는다.
     */
    private fun enqueue(work: suspend () -> Unit) {
        val previous = lastWork
        lastWork = scope.launch {
            previous.join()
            work()
        }
    }
}

/** 컴포지션 수명에 묶인 로그 디렉터리 상태. 첫 조합에서 한 번 읽어 둔다. */
@Composable
fun rememberLogDirectoryState(diagnostics: DiagnosticsUseCases): LogDirectoryState {
    val scope = rememberCoroutineScope()
    val state = remember(scope, diagnostics) { LogDirectoryState(scope, diagnostics) }
    LaunchedEffect(state) { state.refresh() }
    return state
}
