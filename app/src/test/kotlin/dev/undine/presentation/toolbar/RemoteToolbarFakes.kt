package dev.undine.presentation.toolbar

import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val REMOTE = "origin"
internal val BRANCH_REF = RefName("refs/heads/main")

internal fun branchWith(
    ahead: Int,
    behind: Int,
    // RefGateway 가 주는 형식 그대로 — 짧은 이름이다. 긴 형식으로 두면 실제 입력을 검증하지 못한다.
    upstream: RefName? = RefName("origin/main"),
): Branch = Branch(
    name = BRANCH_REF,
    target = CommitId.of("a".repeat(40)),
    isCurrent = true,
    isRemote = false,
    upstream = upstream,
    ahead = ahead,
    behind = behind,
)

internal fun remoteRef(name: String): RemoteRef = RemoteRef(
    remote = REMOTE,
    name = RefName(name),
    target = CommitId.of("b".repeat(40)),
)

/**
 * 원격 작업을 **붙잡아 둘 수 있는** Gateway 대역.
 *
 * [gate] 를 주면 작업이 그 자리에서 멈춘다 — "진행 중" 상태를 검증할 수 있게 하기 위해서다.
 * [ignoreCancellation] 은 취소 뒤 늦게 끝나는 작업을 흉내 낸다 (늦은 콜백이 취소 상태를 덮는지 확인용).
 */
internal class FakeRemoteGateway(
    var gate: CompletableDeferred<Unit>? = null,
    var ignoreCancellation: Boolean = false,
) : RemoteGateway {

    var fetchResult: List<RemoteRef> = emptyList()
    var pushResult: PushResult = PushResult.Accepted
    var failure: UndineException? = null

    var fetchCalls: Int = 0
        private set
    var pullCalls: Int = 0
        private set
    var pushCalls: Int = 0
        private set
    var lastRemote: String? = null
        private set
    var lastPushRef: RefName? = null
        private set
    var lastPushForce: Boolean? = null
        private set

    /** 마지막으로 받은 진행률 콜백 — 작업이 끝난 뒤 늦게 호출해 볼 수 있다. */
    var lastProgressCallback: ((Progress) -> Unit)? = null
        private set

    /**
     * [lastProgressCallback] 이 등록된 순간 완료된다.
     *
     * 상태 홀더가 작업을 **자기 스코프에 launch** 하므로, `fetch()` 가 돌아온 시점에 그 본문이 아직
     * 실행되지 않았을 수 있다 — `Unconfined` 스코프에서는 즉시 실행돼 가려지지만 전용 스레드
     * 디스패처에서는 큐에 남는다. 콜백을 쓰는 테스트는 이 신호를 기다린다.
     */
    val progressCallbackRegistered = CompletableDeferred<(Progress) -> Unit>()

    override suspend fun clone(url: String, into: RepositoryPath, onProgress: (Progress) -> Unit) {
        error("툴바는 clone 을 호출하지 않는다")
    }

    override suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef> {
        fetchCalls++
        lastRemote = remote
        lastProgressCallback = onProgress
        progressCallbackRegistered.complete(onProgress)
        awaitGate()
        failure?.let { throw it }
        return fetchResult
    }

    override suspend fun pull(remote: String, onProgress: (Progress) -> Unit) {
        pullCalls++
        lastRemote = remote
        lastProgressCallback = onProgress
        progressCallbackRegistered.complete(onProgress)
        awaitGate()
        failure?.let { throw it }
    }

    override suspend fun push(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushResult {
        pushCalls++
        lastPushRef = ref
        lastPushForce = force
        lastProgressCallback = onProgress
        progressCallbackRegistered.complete(onProgress)
        awaitGate()
        failure?.let { throw it }
        return pushResult
    }

    private suspend fun awaitGate() {
        val pending = gate ?: return
        if (ignoreCancellation) withContext(NonCancellable) { pending.await() } else pending.await()
    }
}

/**
 * 대역 Gateway 로 만든 툴바 상태. 코루틴은 [Dispatchers.Unconfined] 로 돌려 시작·취소가
 * 호출 스레드에서 즉시 이어지게 한다 — 테스트가 시간에 의존하지 않는다.
 */
internal fun toolbarStateWith(
    remoteGateway: RemoteGateway,
    remotes: List<String> = listOf(REMOTE),
    branch: Branch? = branchWith(ahead = 0, behind = 0),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
): RemoteToolbarState = RemoteToolbarState(
    scope = scope,
    fetchRemote = FetchRemoteUseCase(remoteGateway),
    pullRemote = PullRemoteUseCase(remoteGateway),
    pushRemote = PushRemoteUseCase(remoteGateway),
    remotes = remotes,
    branch = branch,
)
