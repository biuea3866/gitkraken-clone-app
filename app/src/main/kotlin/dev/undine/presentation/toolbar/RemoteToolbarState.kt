package dev.undine.presentation.toolbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.domain.Branch
import dev.undine.domain.Progress
import dev.undine.domain.RefName
import dev.undine.domain.PushResult
import dev.undine.domain.UndineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 원격 추적 브랜치 참조의 접두사. `refs/remotes/origin/main` 의 `refs/remotes/` 부분이다. */
private const val REMOTE_REF_PREFIX = "refs/remotes/"

/** 원격 버튼이 왜 비활성인지 — 비활성 버튼만 두고 이유를 숨기지 않기 위해 상태로 노출한다. */
enum class RemoteToolbarNotice {
    /** 저장소에 등록된 원격이 없다. */
    NO_REMOTE,

    /** 브랜치가 아닌 커밋에 체크아웃돼 있어 올릴 대상 참조가 없다. */
    DETACHED_HEAD,

    /**
     * 현재 브랜치에 업스트림이 없어 **어느 원격으로 올릴지 정해지지 않았다**.
     *
     * 이때 push 를 열어 두면 화면이 경고한 원격과 실제로 올라가는 원격이 달라질 수 있다 —
     * 대상을 확정할 수 없으면 올리지 않는다. fetch·pull 은 원격을 직접 지정하므로 영향받지 않는다.
     */
    NO_UPSTREAM,
}

/**
 * 툴바의 원격 작업 상태 홀더 — 시작·진행·취소·결과를 소유한다 (compose-ui 규칙 1).
 *
 * UseCase 만 호출하고 Gateway 는 알지 못한다 (레이어 규칙 3). 원격 목록과 현재 브랜치는
 * **배선이 주입한다** — 원격 존재 여부를 묻는 계약이 없어 UND-26 이 아는 값을 [updateContext] 로 넣는다
 * (wave 3 결정 A4).
 *
 * 한 번에 한 작업만 돈다. 저장소 핸들은 스레드 안전하지 않아 동시 실행이 손상을 부르므로,
 * 진행 중에는 원격 버튼 전체가 비활성이다 — 같은 작업 버튼 비활성 요구를 포함하는 더 넓은 규칙이다.
 *
 * 이미 끝난 작업의 늦은 콜백은 [generation] 으로 막는다 — 결과가 확정된 뒤 도착한 진행률이
 * 다음 작업의 표시를 흔들지 않게 한다.
 *
 * **취소는 결과를 확정하지 않는다.** 취소 요청은 [cancelRequested] 로 즉시 화면에 반영하고,
 * 최종 결과는 명령이 실제로 끝난 뒤 정한다 — JGit 호출은 중간에 끊기지 않아 push·pull 이
 * 적용된 뒤에 취소가 감지될 수 있고, 그때 화면을 "취소됨" 으로 닫으면 적용 사실이 숨는다.
 */
@Stable
class RemoteToolbarState(
    private val scope: CoroutineScope,
    private val fetchRemote: FetchRemoteUseCase,
    private val pullRemote: PullRemoteUseCase,
    private val pushRemote: PushRemoteUseCase,
    remotes: List<String> = emptyList(),
    branch: Branch? = null,
) {
    var remotes: List<String> by mutableStateOf(remotes)
        private set

    var branch: Branch? by mutableStateOf(branch)
        private set

    var runningOperation: RemoteOperation? by mutableStateOf(null)
        private set

    /** 취소를 요청했고 명령이 아직 끝나지 않았다. 결과가 정해지면 다시 `false` 가 된다. */
    var cancelRequested: Boolean by mutableStateOf(false)
        private set

    /** 0.0~1.0. 불확정 구간에서는 직전 값을 유지해 표시가 뒤로 가지 않는다. */
    var progressFraction: Float by mutableStateOf(0f)
        private set

    /** Gateway 가 전달한 JGit 단계명. 번역 대상이 아니라 원문 그대로 보여준다. */
    var phase: String by mutableStateOf("")
        private set

    var outcome: RemoteOperationOutcome? by mutableStateOf(null)
        private set

    private var generation: Int = 0
    private var runningJob: Job? = null

    val ahead: Int get() = branch?.ahead ?: 0
    val behind: Int get() = branch?.behind ?: 0

    /**
     * fetch·pull 이 향하는 원격. 두 연산은 원격을 인자로 받으므로 원격 선택 UI 가 없는 동안은
     * 주입된 목록의 첫 항목을 쓴다.
     */
    val fetchTargetRemote: String? get() = remotes.firstOrNull()

    /**
     * push 가 **실제로** 향하는 원격 — 현재 브랜치의 업스트림에서 뽑는다.
     *
     * 목록의 첫 원격이 아니다. push 구현은 브랜치 업스트림(`branch.<name>.remote`)으로 올리므로,
     * 첫 원격을 경고에 적으면 **다른 원격을 경고하고 업스트림에 force push 하는** 어긋남이 생긴다.
     * 화면의 경고·복구 안내와 실제 대상이 이 하나를 함께 읽게 한다.
     *
     * 업스트림이 없거나 그 원격이 주입된 목록에 없으면 `null` 이다 — 대상을 확정할 수 없으면 올리지 않는다.
     */
    val pushTargetRemote: String? get() = branch?.upstream?.let { remoteNameOf(it, remotes) }

    /** 비활성 사유. 실행 가능하면 `null` 이다. */
    val notice: RemoteToolbarNotice?
        get() = when {
            remotes.isEmpty() -> RemoteToolbarNotice.NO_REMOTE
            branch == null -> RemoteToolbarNotice.DETACHED_HEAD
            pushTargetRemote == null -> RemoteToolbarNotice.NO_UPSTREAM
            else -> null
        }

    fun isEnabled(operation: RemoteOperation): Boolean = runningOperation == null && when (operation) {
        RemoteOperation.PUSH -> pushTargetRemote != null && branch != null
        RemoteOperation.FETCH, RemoteOperation.PULL -> fetchTargetRemote != null
    }


    /** 배선(UND-26)이 아는 원격 목록·현재 브랜치를 넣는다. */
    fun updateContext(remotes: List<String>, branch: Branch?) {
        this.remotes = remotes
        this.branch = branch
    }

    fun fetch() {
        val remote = fetchTargetRemote ?: return
        start(RemoteOperation.FETCH) { onProgress ->
            RemoteOperationOutcome.Fetched(refCount = fetchRemote.execute(remote, onProgress).size)
        }
    }

    fun pull() {
        val remote = fetchTargetRemote ?: return
        start(RemoteOperation.PULL) { onProgress ->
            pullRemote.execute(remote, onProgress)
            RemoteOperationOutcome.Pulled
        }
    }

    /**
     * 현재 브랜치를 올린다.
     *
     * `force = true` 는 **사용자 확인을 받은 뒤에만** 호출한다 (`RemoteToolbar` 의 force push 확인).
     * 백업 ref 와 force-with-lease 는 Gateway 의 책임이라 여기서 중복 구현하지 않는다.
     */
    fun push(force: Boolean = false) {
        // 실제 push 대상(업스트림)을 확정하지 못하면 시작하지 않는다 — 경고한 원격과 어긋날 수 있다.
        if (pushTargetRemote == null) return
        val ref = branch?.name ?: return
        start(RemoteOperation.PUSH, forcePush = force) { onProgress ->
            when (val result = pushRemote.execute(ref, force, onProgress)) {
                PushResult.Accepted -> RemoteOperationOutcome.Pushed(force)
                is PushResult.Rejected -> RemoteOperationOutcome.PushRejected(result.reason)
            }
        }
    }

    /**
     * 진행 중인 작업의 취소를 **요청**한다.
     *
     * 여기서 결과를 확정하지 않는다 — 확정하면 push·pull 이 이미 적용된 채로 끝난 경우에도 화면이
     * "취소됨" 으로 닫혀 부분 적용과 force push 복구 필요성이 숨는다. 즉시 반영되는 것은
     * [cancelRequested] 이고, 최종 결과는 명령이 끝난 뒤 [start] 의 코루틴이 정한다.
     *
     * 명령이 아직 저장소를 잡고 있으므로 [runningOperation] 은 그대로 두고 버튼도 계속 잠근다.
     */
    fun cancel() {
        if (runningOperation == null || cancelRequested) return
        cancelRequested = true
        runningJob?.cancel()
    }

    /** 결과 안내를 닫는다. */
    fun dismissOutcome() {
        outcome = null
    }

    private fun start(
        operation: RemoteOperation,
        forcePush: Boolean = false,
        action: suspend (onProgress: (Progress) -> Unit) -> RemoteOperationOutcome,
    ) {
        if (runningOperation != null) return
        val startedGeneration = ++generation
        runningOperation = operation
        cancelRequested = false
        progressFraction = 0f
        phase = ""
        outcome = null
        runningJob = scope.launch {
            try {
                // 취소를 요청했더라도 명령이 결과를 남겼다면 그 결과를 알린다 — 적용된 push 를
                // 취소로 덮으면 사용자는 원격이 그대로라고 읽는다.
                finish(startedGeneration, action { report(startedGeneration, it) })
            } catch (cancellation: CancellationException) {
                // 결과를 남기지 못한 취소만 취소로 확정한다. 삼키지 않고 다시 던져 코루틴 취소를 완성한다.
                finish(startedGeneration, RemoteOperationOutcome.Cancelled(operation, forcePush))
                throw cancellation
            } catch (failure: UndineException) {
                finish(
                    startedGeneration,
                    RemoteOperationOutcome.Failed(operation, remoteFailureKindOf(failure)),
                )
            }
        }
    }

    /**
     * 진행률을 **화면 스코프로 옮겨** 반영한다.
     *
     * JGit 진행 콜백은 Gateway 의 IO 스레드에서 올라온다 — 여기서 그대로 쓰면 Compose 상태를
     * UI 스레드 밖에서 바꾸게 된다. [scope] 는 화면이 만든 컴포지션 스코프라 그 디스패처가 곧 UI
     * 컨텍스트이며, 다른 상태 갱신이 이미 이 스코프 위에서 일어난다.
     *
     * 세대 검사는 **옮긴 뒤에** 한 번 더 한다 — 옮기는 사이 작업이 끝나면 늦은 진행률이 다음 작업의
     * 표시를 흔든다.
     */
    private fun report(startedGeneration: Int, progress: Progress) {
        scope.launch {
            if (startedGeneration != generation) return@launch
            val next = progress.completedFraction.toFloat().coerceIn(0f, 1f)
            if (next > progressFraction) progressFraction = next
            phase = progress.phase
        }
    }

    private fun finish(startedGeneration: Int, result: RemoteOperationOutcome) {
        if (startedGeneration != generation) return
        // 이 작업의 세대를 닫는다 — 이후 도착하는 진행·완료 보고는 무시된다.
        generation++
        runningOperation = null
        cancelRequested = false
        runningJob = null
        outcome = result
    }
}

/**
 * 컴포지션 수명에 묶인 툴바 상태.
 *
 * [remotes]·[branch] 는 배선이 매 조합마다 최신 값을 넘긴다 — 상태 홀더는 그 값을 보관만 하고
 * 원격 존재 여부를 스스로 조회하지 않는다.
 */
@Composable
fun rememberRemoteToolbarState(
    fetchRemote: FetchRemoteUseCase,
    pullRemote: PullRemoteUseCase,
    pushRemote: PushRemoteUseCase,
    remotes: List<String>,
    branch: Branch?,
): RemoteToolbarState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) {
        RemoteToolbarState(scope, fetchRemote, pullRemote, pushRemote, remotes, branch)
    }
    SideEffect { state.updateContext(remotes, branch) }
    return state
}

/**
 * 업스트림 추적 이름에서 원격 이름을 뽑는다.
 *
 * `RefGateway` 는 업스트림을 **짧은 이름**(`origin/main`)으로 준다. 전체 이름
 * (`refs/remotes/origin/main`)으로 들어오는 경우도 접두사를 떼고 같은 규칙으로 본다 —
 * 어느 형식이든 같은 원격으로 읽혀야 한다.
 *
 * 원격 이름에 `/` 가 들어갈 수 있어(`team/fork`) 첫 조각을 자르지 않고 **주어진 원격 목록과
 * 대조**한다 — 가장 긴 접두사가 실제 원격이다. 목록에 없는 원격을 가리키면 `null` 이다.
 */
private fun remoteNameOf(upstream: RefName, remotes: List<String>): String? {
    val tracking = upstream.value.removePrefix(REMOTE_REF_PREFIX)
    return remotes.filter { tracking.startsWith("$it/") }.maxByOrNull(String::length)
}
