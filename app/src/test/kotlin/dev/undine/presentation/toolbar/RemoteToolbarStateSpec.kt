package dev.undine.presentation.toolbar

import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 툴바 상태 홀더 — 원격 작업의 시작·진행·취소·결과를 소유한다. */
/** 화면 스코프 전용 스레드 이름 — 갱신이 정말 이 스레드에서 일어났는지 대조할 기준이다. */
private const val UI_THREAD_NAME = "undine-ui-test"

class RemoteToolbarStateSpec : FunSpec({

    test("fetch 를 시작하면 진행 중 상태가 되고 같은 작업을 다시 시작할 수 없다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate)
        val state = toolbarStateWith(gateway)

        state.fetch()

        state.runningOperation shouldBe RemoteOperation.FETCH
        state.isEnabled(RemoteOperation.FETCH) shouldBe false

        state.fetch()
        gateway.fetchCalls shouldBe 1

        gate.complete(Unit)
        state.runningOperation.shouldBeNull()
    }

    test("진행률은 Double 을 Float 으로 옮기고 불확정 구간에서 뒤로 가지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate)
        val state = toolbarStateWith(gateway)

        state.fetch()
        val report = requireNotNull(gateway.lastProgressCallback)

        report(Progress(0.0, "Finding sources"))
        state.progressFraction shouldBe 0f

        report(Progress(0.4, "Receiving objects"))
        state.progressFraction shouldBe 0.4f
        state.phase shouldBe "Receiving objects"

        // 작업량을 모르는 구간 — Gateway 는 직전 값을 그대로 다시 올린다.
        report(Progress(0.4, "Resolving deltas"))
        state.progressFraction shouldBe 0.4f
        state.phase shouldBe "Resolving deltas"

        // 진행률 계산이 튀어 뒤로 가더라도 표시값은 후퇴하지 않는다.
        report(Progress(0.1, "Resolving deltas"))
        state.progressFraction shouldBe 0.4f

        // 완료 보고는 Double 1.0 이 Float 1f 로 그대로 옮겨져야 한다.
        report(Progress(1.0, "Done"))
        state.progressFraction shouldBe 1f
        state.phase shouldBe "Done"

        gate.complete(Unit)
    }

    test("fetch 가 끝나면 갱신된 원격 참조 수가 결과로 남는다") {
        val gateway = FakeRemoteGateway()
        gateway.fetchResult = listOf(remoteRef("refs/remotes/origin/main"), remoteRef("refs/remotes/origin/dev"))
        val state = toolbarStateWith(gateway)

        state.fetch()

        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 2)
        state.runningOperation.shouldBeNull()
    }

    test("pull 이 끝나면 성공 결과가 남고 갱신 건수를 주장하지 않는다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway)

        state.pull()

        gateway.pullCalls shouldBe 1
        state.outcome shouldBe RemoteOperationOutcome.Pulled
    }

    test("취소하면 즉시 취소 상태가 되고 진행 표시가 사라진다") {
        val gate = CompletableDeferred<Unit>()
        val state = toolbarStateWith(FakeRemoteGateway(gate))

        state.fetch()
        state.cancel()

        state.runningOperation.shouldBeNull()
        state.outcome shouldBe RemoteOperationOutcome.Cancelled(RemoteOperation.FETCH)
        state.isEnabled(RemoteOperation.FETCH) shouldBe true
    }

    test("취소를 요청해도 명령이 끝나기 전에는 결과를 확정하지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate, ignoreCancellation = true)
        val state = toolbarStateWith(gateway)

        state.push()
        state.cancel()

        // 명령이 아직 저장소를 잡고 있다 — 취소 요청만 즉시 보이고 버튼은 계속 잠긴다.
        state.cancelRequested shouldBe true
        state.runningOperation shouldBe RemoteOperation.PUSH
        state.outcome.shouldBeNull()
        state.isEnabled(RemoteOperation.PUSH) shouldBe false

        gate.complete(Unit)

        // 전송이 취소보다 먼저 끝났으므로 적용된 사실을 알린다 — 취소로 덮으면 원격이 그대로인 줄 읽는다.
        state.outcome shouldBe RemoteOperationOutcome.Pushed(force = false)
        state.cancelRequested shouldBe false
        state.runningOperation.shouldBeNull()
    }

    test("취소보다 pull 이 먼저 끝나면 병합된 사실을 취소로 덮지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val state = toolbarStateWith(FakeRemoteGateway(gate, ignoreCancellation = true))

        state.pull()
        state.cancel()
        gate.complete(Unit)

        state.outcome shouldBe RemoteOperationOutcome.Pulled
        state.cancelRequested shouldBe false
    }

    test("결과가 확정된 뒤 늦게 도착한 진행 보고는 표시를 흔들지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate)
        val state = toolbarStateWith(gateway)

        state.fetch()
        val report = requireNotNull(gateway.lastProgressCallback)
        report(Progress(0.3, "Receiving objects"))
        gate.complete(Unit)
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)

        // 전송 스레드가 결과 확정 뒤에 남긴 콜백이 도착할 수 있다.
        report(Progress(0.9, "Resolving deltas"))

        state.progressFraction shouldBe 0.3f
        state.phase shouldBe "Receiving objects"
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)
    }

    test("취소로 끝난 pull 은 병합이 시작됐을 수 있음을 담은 취소 결과로 남는다") {
        val state = toolbarStateWith(FakeRemoteGateway(CompletableDeferred()))

        state.pull()
        state.cancel()

        state.outcome shouldBe RemoteOperationOutcome.Cancelled(RemoteOperation.PULL)
        state.runningOperation.shouldBeNull()
    }

    test("취소로 끝난 force push 는 덮어쓰기 가능성을 구분해 남긴다") {
        val state = toolbarStateWith(FakeRemoteGateway(CompletableDeferred()))

        state.push(force = true)
        state.cancel()

        state.outcome shouldBe RemoteOperationOutcome.Cancelled(RemoteOperation.PUSH, forcePush = true)
    }

    test("취소한 뒤에도 다음 작업을 시작할 수 있다") {
        val gateway = FakeRemoteGateway(CompletableDeferred())
        val state = toolbarStateWith(gateway)
        state.fetch()
        state.cancel()

        gateway.gate = null
        state.fetch()

        gateway.fetchCalls shouldBe 2
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)
    }

    test("non-fast-forward 거절은 실패가 아니라 거절 결과로 남는다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)

        state.push()

        state.outcome shouldBe
            RemoteOperationOutcome.PushRejected(PushResult.RejectReason.NON_FAST_FORWARD)
    }

    test("인증 실패는 AUTHENTICATION 종류의 실패로 남는다") {
        val gateway = FakeRemoteGateway()
        gateway.failure = UndineException.AuthenticationFailed(REMOTE)
        val state = toolbarStateWith(gateway)

        state.push()

        state.outcome shouldBe RemoteOperationOutcome.Failed(
            operation = RemoteOperation.PUSH,
            kind = RemoteFailureKind.AUTHENTICATION,
        )
    }

    test("예상 못 한 Git 실패는 UNEXPECTED 종류로 남는다") {
        val gateway = FakeRemoteGateway()
        gateway.failure = UndineException.GitOperationFailed("fetch")
        val state = toolbarStateWith(gateway)

        state.fetch()

        state.outcome shouldBe RemoteOperationOutcome.Failed(
            operation = RemoteOperation.FETCH,
            kind = RemoteFailureKind.UNEXPECTED,
        )
    }

    test("원격이 없으면 원격 작업을 시작하지 않고 사유를 알린다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway, remotes = emptyList())

        state.fetch()
        state.pull()
        state.push()

        gateway.fetchCalls shouldBe 0
        gateway.pullCalls shouldBe 0
        gateway.pushCalls shouldBe 0
        state.isEnabled(RemoteOperation.FETCH) shouldBe false
        state.notice shouldBe RemoteToolbarNotice.NO_REMOTE
    }

    test("브랜치가 아닌 커밋에 체크아웃돼 있으면 push 를 시작하지 않는다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway, branch = null)

        state.push()

        gateway.pushCalls shouldBe 0
        state.isEnabled(RemoteOperation.PUSH) shouldBe false
        state.isEnabled(RemoteOperation.FETCH) shouldBe true
        state.notice shouldBe RemoteToolbarNotice.DETACHED_HEAD
    }

    test("push 는 현재 브랜치 참조와 force 플래그를 그대로 전달한다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway)

        state.push(force = true)

        gateway.lastPushRef shouldBe BRANCH_REF
        gateway.lastPushForce shouldBe true
        state.outcome shouldBe RemoteOperationOutcome.Pushed(force = true)
    }

    test("ahead·behind 는 주입된 브랜치 값을 그대로 노출하고 배선이 갱신할 수 있다") {
        val state = toolbarStateWith(FakeRemoteGateway(), branch = branchWith(ahead = 2, behind = 3))

        state.ahead shouldBe 2
        state.behind shouldBe 3

        state.updateContext(remotes = listOf(REMOTE, "upstream"), branch = branchWith(ahead = 0, behind = 5))

        state.ahead shouldBe 0
        state.behind shouldBe 5
        state.remotes shouldBe listOf(REMOTE, "upstream")
    }

    test("결과를 확인하면 안내가 사라진다") {
        val state = toolbarStateWith(FakeRemoteGateway())
        state.fetch()
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)

        state.dismissOutcome()

        state.outcome.shouldBeNull()
    }

    test("새 작업을 시작하면 직전 결과 안내와 진행률을 지운다") {
        val firstGate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(firstGate)
        val state = toolbarStateWith(gateway)
        state.fetch()
        requireNotNull(gateway.lastProgressCallback).invoke(Progress(0.7, "Receiving objects"))
        state.progressFraction shouldBe 0.7f
        firstGate.complete(Unit)
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)

        gateway.gate = CompletableDeferred()
        state.pull()

        state.outcome.shouldBeNull()
        state.progressFraction shouldBe 0f
        state.runningOperation shouldBe RemoteOperation.PULL
    }

    test("push 대상은 목록의 첫 원격이 아니라 현재 브랜치의 업스트림이다") {
        // 실제 push 구현은 branch.<name>.remote 로 올린다. 첫 원격을 경고에 적으면
        // "upstream 을 경고하고 upstream 에 올린다" 가 깨져 다른 원격을 경고하게 된다.
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            remotes = listOf("backup", "origin"),
            branch = branchWith(ahead = 1, behind = 0, upstream = RefName("origin/main")),
        )

        state.fetchTargetRemote shouldBe "backup"
        state.pushTargetRemote shouldBe "origin"
        state.isEnabled(RemoteOperation.PUSH) shouldBe true
        state.notice.shouldBeNull()
    }

    test("원격 이름에 슬래시가 있어도 업스트림에서 가장 긴 원격 이름을 고른다") {
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            remotes = listOf("team", "team/fork"),
            branch = branchWith(ahead = 0, behind = 0, upstream = RefName("team/fork/main")),
        )

        state.pushTargetRemote shouldBe "team/fork"
    }

    test("전체 참조 이름으로 들어온 업스트림도 같은 원격으로 읽는다") {
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            remotes = listOf("backup", "origin"),
            branch = branchWith(ahead = 1, behind = 0, upstream = RefName("refs/remotes/origin/main")),
        )

        state.pushTargetRemote shouldBe "origin"
    }

    test("업스트림이 없으면 push 를 열지 않고 사유를 알린다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(
            gateway,
            remotes = listOf("origin"),
            branch = branchWith(ahead = 1, behind = 0, upstream = null),
        )

        state.pushTargetRemote.shouldBeNull()
        state.isEnabled(RemoteOperation.PUSH) shouldBe false
        state.notice shouldBe RemoteToolbarNotice.NO_UPSTREAM

        // 대상을 확정할 수 없으면 force push 도 시작하지 않는다.
        state.push(force = true)
        state.runningOperation.shouldBeNull()
        gateway.pushCalls shouldBe 0
    }

    test("업스트림 원격이 주입된 목록에 없으면 push 를 열지 않는다") {
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            remotes = listOf("origin"),
            branch = branchWith(ahead = 1, behind = 0, upstream = RefName("gone/main")),
        )

        state.pushTargetRemote.shouldBeNull()
        state.notice shouldBe RemoteToolbarNotice.NO_UPSTREAM
    }

    test("업스트림이 없어도 fetch·pull 은 원격을 직접 지정하므로 열려 있다") {
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            remotes = listOf("origin"),
            branch = branchWith(ahead = 0, behind = 0, upstream = null),
        )

        state.isEnabled(RemoteOperation.FETCH) shouldBe true
        state.isEnabled(RemoteOperation.PULL) shouldBe true
    }

    test("IO 스레드에서 올라온 진행률은 화면 스코프를 거쳐 반영된다") {
        // JGit 진행 콜백은 Gateway 의 IO 스레드에서 온다. 상태 홀더가 그 자리에서 Compose 상태를 쓰면
        // UI 스레드 밖 갱신이 된다. 여기서는 **화면 스레드를 붙잡아 두고** 콜백을 때려, 갱신이
        // 즉시 일어나지 않고 그 스레드의 차례를 기다리는지 본다 — 그것이 곧 스코프를 거쳤다는 증거다.
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate)
        val uiDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, UI_THREAD_NAME)
        }.asCoroutineDispatcher()

        try {
            val state = toolbarStateWith(gateway, scope = CoroutineScope(uiDispatcher))
            withContext(uiDispatcher) { state.fetch() }
            // fetch 는 자기 스코프에 launch 하므로 돌아온 시점에 본문이 아직 큐에 있을 수 있다.
            // 콜백이 실제로 등록될 때까지 기다린다 — 여기서 추측하면 전체 스위트 부하에서 깨진다.
            val callback = gateway.progressCallbackRegistered.await()

            // 화면 스레드를 블로킹으로 점유한다 (suspend 로 비우면 큐가 그대로 소비된다).
            // 점유가 **시작된 것을 확인한 뒤에** 콜백을 때린다 — launch 가 아직 큐에 있는 동안
            // 때리면 갱신이 먼저 실행돼 아래 단정이 우연히 실패한다(경쟁).
            val occupied = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holding = CoroutineScope(uiDispatcher).launch {
                occupied.countDown()
                release.await()
            }
            occupied.await()

            withContext(Dispatchers.IO) { callback(Progress(0.4, "Receiving objects")) }

            // 콜백 스레드에서 직접 썼다면 여기서 이미 0.4 다.
            state.progressFraction shouldBe 0f

            release.countDown()
            holding.join()
            withContext(uiDispatcher) { /* 큐를 비운다 */ }

            state.progressFraction shouldBe 0.4f
            state.phase shouldBe "Receiving objects"
        } finally {
            gate.complete(Unit)
            uiDispatcher.close()
        }
    }

    test("작업이 끝난 뒤 늦게 도착한 진행률은 표시를 흔들지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeRemoteGateway(gate)
        val state = toolbarStateWith(gateway)
        state.fetch()
        val callback = requireNotNull(gateway.lastProgressCallback)
        gate.complete(Unit)
        state.outcome shouldBe RemoteOperationOutcome.Fetched(refCount = 0)

        // 세대가 닫힌 뒤 도착한 보고다.
        withContext(Dispatchers.IO) { callback(Progress(0.9, "Late")) }

        state.progressFraction shouldBe 0f
        state.phase shouldBe ""
    }

})
