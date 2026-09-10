package dev.undine.presentation.toolbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.toolbar.FastForwardOutcome
import dev.undine.domain.Branch
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * 덮어쓸 대상이 화면에 이미 드러나 있는지 — 결과 안내가 브랜치 이름을 말할지 가른다.
 */
enum class ForcePushTarget {
    /** 툴바가 올리는 현재 브랜치. 화면이 이미 그 브랜치를 보여주고 있다. */
    CURRENT,

    /** 사이드바에서 지목한 브랜치. 안내가 **어느 브랜치를** 덮어썼는지 이름으로 말해야 한다. */
    NAMED,
}

/**
 * 확인을 기다리는 덮어쓰기 — 아직 아무것도 보내지 않았다.
 *
 * 툴바의 더 보기와 지목 올리기의 non-fast-forward 거절이 **이 하나로 모인다.** 확인 문장·버튼을
 * 경로마다 새로 만들면 그것이 두 번째 안전 기준이 되고, 사용자는 어느 쪽이 안전한지 알 수 없다
 * (결정 D2·D11).
 */
@Immutable
data class ForcePushPrompt(
    val branch: RefName,
    val remote: String,
    val target: ForcePushTarget,
)

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
@Suppress("TooManyFunctions") // 툴바의 현재 브랜치 조작과 사이드바의 지목 조작이 한 홀더의 상태 전이다.
class RemoteToolbarState(
    private val scope: CoroutineScope,
    private val actions: RemoteActions,
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

    /**
     * 확인을 기다리는 덮어쓰기. `null` 이면 확인 중인 것이 없다.
     *
     * 화면은 이 값 하나만 보고 확인 문장을 그린다 — 툴바에서 시작했든 사이드바의 거절에서
     * 올라왔든 같은 경고·같은 확인을 지난다.
     */
    var forcePushPrompt: ForcePushPrompt? by mutableStateOf(null)
        private set

    /**
     * 실행 이력 기록만 실패한 사유. null 이 아니면 **원격 작업은 끝났고 이력 항목만 남지 않았다** —
     * push 는 원격에 올라갔고, 지목 받기는 브랜치가 이미 옮겨졌다.
     *
     * 결과 안내는 [remoteOperationMessage] 가 문장·톤으로 옮기므로 여기서는 값을 **전달만** 한다 —
     * 이력 화면처럼 이 사실을 따로 쓰는 표면을 위해 남겨 둔다 (결정 G30 3).
     */
    var undoRecordFailure: UndineException? by mutableStateOf(null)
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
    val pushTargetRemote: String? get() = branch?.let { trackedRemoteOf(it, remotes) }

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

    /**
     * 지목 조작의 가용성. **사이드바가 스스로 판정하지 않고 이 결과를 읽는다** (결정 D4) —
     * 진입점이 조건을 다시 쓰면 그것이 두 번째 판정이 된다.
     */
    fun branchEntryOf(branch: Branch, operation: BranchRemoteOperation): BranchRemoteEntry =
        BranchRemoteEntry(
            operation = operation,
            blockedReason = branchRemoteRefusalOf(branch, operation, remotes),
            busy = runningOperation != null,
        )

    /**
     * 지목한 브랜치를 올린다 — **새 push 경로를 만들지 않고** 툴바와 같은 push UseCase 에
     * 그 브랜치의 참조를 넘긴다 (결정 D2).
     *
     * 처음 시도는 언제나 `force = false` 다. 원격이 non-fast-forward 로 거절하면 **툴바와 같은
     * 확인 문장**([forcePushPrompt])을 띄우고 멈춘다 — 사용자가 그 확인을 누르기 전에는 아무것도
     * 덮어쓰지 않는다 (결정 D11).
     */
    fun pushBranch(branch: Branch) {
        if (!branchEntryOf(branch, BranchRemoteOperation.PUSH).enabled) return
        // 가드와 같은 함수를 본다 — 각자 판정하면 여기서 조용히 끝나 버튼이 먹통이 된다.
        val remote = pushRemoteOf(branch, remotes) ?: return
        pushBranchRef(branch.name, remote, force = false)
    }

    /**
     * 툴바의 더 보기에서 덮어쓰기를 요청한다 — 확인 문장을 띄울 뿐 **아직 아무것도 보내지 않는다**.
     */
    fun requestForcePush() {
        val ref = branch?.name ?: return
        val remote = pushTargetRemote ?: return
        forcePushPrompt = ForcePushPrompt(ref, remote, ForcePushTarget.CURRENT)
    }

    /** 확인을 물린다. 아무것도 보내지 않는다. */
    fun dismissForcePush() {
        forcePushPrompt = null
    }

    /**
     * 확인을 받은 덮어쓰기 — **확인 문장이 말한 그 대상에만** `force = true` 가 나간다.
     *
     * 툴바에서 시작했으면 현재 브랜치 경로를, 사이드바의 거절에서 올라왔으면 그 지목 브랜치
     * 경로를 그대로 다시 탄다. 두 경우 모두 이 확인 하나를 지난 뒤에만 덮어쓴다.
     */
    fun confirmForcePush() {
        val prompt = forcePushPrompt ?: return
        forcePushPrompt = null
        // 두 경로 모두 **확인 문장이 잡아 둔 ref·remote** 로 나간다 — 지금 상태를 다시 읽지 않는다.
        when (prompt.target) {
            ForcePushTarget.CURRENT -> pushCurrent(prompt.branch, prompt.remote, force = true)
            ForcePushTarget.NAMED -> pushBranchRef(prompt.branch, prompt.remote, force = true)
        }
    }

    /**
     * 지목 push 한 번. [remote] 는 거절됐을 때 확인 문장이 적을 대상이라 함께 들고 다닌다 —
     * 경고에 적힌 원격과 실제로 나가는 원격이 갈리면 확인이 근거를 잃는다.
     */
    private fun pushBranchRef(ref: RefName, remote: String, force: Boolean) {
        start(RemoteOperation.PUSH, forcePush = force) { onProgress ->
            val pushed = actions.pushRemote.execute(ref, remote, force, onProgress)
            undoRecordFailure = pushed.undoRecordFailure
            when (val result = pushed.result) {
                PushResult.Accepted -> RemoteOperationOutcome.BranchPushed(ref, force)
                is PushResult.Rejected -> {
                    // 사이드바에는 툴바의 더 보기 메뉴가 없다. 덮어쓰기로 가는 길을 여기서도 **툴바와
                    // 같은 경고·확인** 하나로 두고, 이미 force 로 나간 거절은 다시 묻지 않는다.
                    if (!force && result.reason == PushResult.RejectReason.NON_FAST_FORWARD) {
                        forcePushPrompt = ForcePushPrompt(ref, remote, ForcePushTarget.NAMED)
                    }
                    RemoteOperationOutcome.PushRejected(result.reason)
                }
            }
        }
    }

    /**
     * 지목한 브랜치를 원격 위치로 빨리 감는다 — 체크아웃도 병합도 하지 않는다 (결정 D1·D3).
     *
     * 툴바의 [pull] 로 몰래 위임하지 않는다. 메뉴가 다른 경로를 부르면 사용자는 무엇이 실행됐는지
     * 모르고, 이 티켓이 지키려는 "대상이 명시적인 것" 과 어긋난다 (결정 D8).
     */
    fun pullBranch(branch: Branch) {
        if (!branchEntryOf(branch, BranchRemoteOperation.PULL).enabled) return
        val remote = trackedRemoteOf(branch, remotes) ?: return
        start(RemoteOperation.PULL) { onProgress ->
            val pulled = actions.fastForwardBranch.execute(branch, remote, onProgress)
            // 옮겨 놓고 기록만 실패한 경우를 여기서 흘리면 화면은 되돌릴 수 있다고 말한다.
            undoRecordFailure = (pulled as? FastForwardOutcome.FastForwarded)?.undoRecordFailure
            RemoteOperationOutcome.BranchPulled(pulled)
        }
    }

    /** 배선(UND-26)이 아는 원격 목록·현재 브랜치를 넣는다. */
    fun updateContext(remotes: List<String>, branch: Branch?) {
        this.remotes = remotes
        this.branch = branch
    }

    fun fetch() {
        val remote = fetchTargetRemote ?: return
        start(RemoteOperation.FETCH) { onProgress ->
            RemoteOperationOutcome.Fetched(refCount = actions.fetchRemote.execute(remote, onProgress).size)
        }
    }

    fun pull() {
        val remote = fetchTargetRemote ?: return
        start(RemoteOperation.PULL) { onProgress ->
            actions.pullRemote.execute(remote, onProgress)
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
        val remote = pushTargetRemote ?: return
        val ref = branch?.name ?: return
        pushCurrent(ref, remote, force)
    }

    /**
     * 현재 브랜치 push 한 번 — **대상을 인자로 받는다.**
     *
     * 확인을 거친 덮어쓰기는 확인 시점에 잡은 ref·remote 를 그대로 넘긴다. 실행 시점에 다시
     * 읽으면 확인 뒤 브랜치를 바꾼 사용자가 **동의하지 않은 대상**을 덮어쓴다.
     */
    private fun pushCurrent(ref: RefName, remote: String, force: Boolean) {
        start(RemoteOperation.PUSH, forcePush = force) { onProgress ->
            val pushed = actions.pushRemote.execute(ref, remote, force, onProgress)
            undoRecordFailure = pushed.undoRecordFailure
            when (val result = pushed.result) {
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
        // 새 작업이 시작되면 앞 작업이 띄운 확인은 근거를 잃는다 — 남겨 두면 다른 작업의 결과 위에
        // 엉뚱한 대상의 덮어쓰기 버튼이 남는다.
        forcePushPrompt = null
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
    actions: RemoteActions,
    remotes: List<String>,
    branch: Branch?,
): RemoteToolbarState {
    val scope = rememberCoroutineScope()
    // actions 도 키다. 저장소가 바뀌면 실행 경로와 실행 이력 범위가 함께 바뀌므로, 이전 저장소의
    // 묶음을 계속 쓰면 **B 를 조작하고 A 의 이력에 기록**한다 (결정 D10, UND-80 과 같은 종류).
    val state = remember(scope, actions) { RemoteToolbarState(scope, actions, remotes, branch) }
    SideEffect { state.updateContext(remotes, branch) }
    return state
}
