package dev.undine.presentation.toolbar

import dev.undine.application.toolbar.FastForwardOutcome
import dev.undine.application.toolbar.FastForwardRefusal
import dev.undine.domain.PushResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.testsupport.spyRecorderOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred

/** 이력 기록만 실패했을 때 기록기가 던지는 사유. 이동 자체의 실패와 구분해 본다. */
private val RECORD_FAILED = UndineException.GitOperationFailed("undo.record")

/**
 * 지목 조작의 **가용성 판정과 실행 경로**. 사이드바가 읽는 답이 여기서 나오므로, 막힌 조건마다
 * 사유가 붙는지와 막힌 항목이 실행되지 않는지를 함께 본다 (결정 D4·D5·D8).
 */
class BranchRemoteOpsSpec : FunSpec({

    test("원격이 없으면 받기·올리기 둘 다 사유와 함께 막힌다") {
        val state = toolbarStateWith(FakeRemoteGateway(), remotes = emptyList())

        BranchRemoteOperation.entries.forEach { operation ->
            val entry = state.branchEntryOf(feature(), operation)
            entry.blockedReason shouldBe BranchRemoteRefusal.NO_REMOTE
            entry.enabled shouldBe false
        }
    }

    test("추적 브랜치가 없으면 받기는 막히고 올리기는 원격이 하나라 열려 있다") {
        // 둘이 갈린다. 받기는 무엇을 받을지 정할 수 없지만, 올리기는 원격에 아직 없는 새 브랜치를
        // 올리는 정상 흐름이고 원격이 하나뿐이라 대상이 모호하지 않다.
        val state = toolbarStateWith(FakeRemoteGateway())
        val fresh = feature(upstream = null)

        val pull = state.branchEntryOf(fresh, BranchRemoteOperation.PULL)
        pull.blockedReason shouldBe BranchRemoteRefusal.NO_UPSTREAM
        pull.enabled shouldBe false

        state.branchEntryOf(fresh, BranchRemoteOperation.PUSH).enabled shouldBe true
    }

    test("현재 체크아웃된 브랜치는 받기만 막히고 올리기는 열려 있다") {
        val state = toolbarStateWith(FakeRemoteGateway(), branch = branchWith(ahead = 0, behind = 0))
        val current = feature(isCurrent = true)

        state.branchEntryOf(current, BranchRemoteOperation.PULL).blockedReason shouldBe
            BranchRemoteRefusal.CURRENT_BRANCH
        state.branchEntryOf(current, BranchRemoteOperation.PUSH).enabled shouldBe true
    }

    test("현재 브랜치와 다른 원격을 추적해도 올릴 수 있다 — 미는 ref 의 업스트림으로 간다") {
        // 옛 규칙은 여기서 막았다. push 가 HEAD 의 업스트림으로 나가던 때의 이야기이고,
        // 지금은 미는 ref 자신의 업스트림으로 가므로 어긋날 일이 없다.
        val state = toolbarStateWith(FakeRemoteGateway(), remotes = listOf("origin", "fork"))
        val onFork = feature(upstream = RefName("fork/feature"))

        state.branchEntryOf(onFork, BranchRemoteOperation.PUSH).enabled shouldBe true
        state.branchEntryOf(onFork, BranchRemoteOperation.PULL).enabled shouldBe true
    }

    test("업스트림이 없어도 원격이 하나뿐이면 올릴 수 있다 — 원격에 아직 없는 새 브랜치다") {
        val state = toolbarStateWith(FakeRemoteGateway(), remotes = listOf("origin"))
        val fresh = feature(upstream = null)

        state.branchEntryOf(fresh, BranchRemoteOperation.PUSH).enabled shouldBe true
    }

    test("업스트림이 없고 원격이 여럿이면 어디로 올릴지 정할 수 없어 막힌다") {
        val state = toolbarStateWith(FakeRemoteGateway(), remotes = listOf("origin", "fork"))
        val fresh = feature(upstream = null)

        state.branchEntryOf(fresh, BranchRemoteOperation.PUSH).blockedReason shouldBe
            BranchRemoteRefusal.AMBIGUOUS_REMOTE
    }

    test("다른 원격 작업이 도는 동안에는 사유 없이 비활성이다 — 곧 풀리는 상태다") {
        val gate = CompletableDeferred<Unit>()
        val state = toolbarStateWith(FakeRemoteGateway(gate))

        state.fetch()

        val entry = state.branchEntryOf(feature(), BranchRemoteOperation.PUSH)
        entry.blockedReason.shouldBeNull()
        entry.busy shouldBe true
        entry.enabled shouldBe false

        gate.complete(Unit)
    }

    test("지목 올리기는 그 브랜치의 참조를 force 없이 기존 push 경로에 넘긴다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway)

        state.pushBranch(feature())

        gateway.lastPushRef shouldBe FEATURE
        // 원격도 함께 넘어간다 — 게이트웨이가 다시 해석하면 확인 문장이 근거를 잃는다 (결정 D19).
        gateway.lastPushRemote shouldBe REMOTE
        gateway.lastPushForce shouldBe false
        state.outcome shouldBe RemoteOperationOutcome.BranchPushed(FEATURE)
    }

    test("확인을 거친 덮어쓰기는 확인 문장이 잡은 원격 그대로 나간다") {
        // 확인 뒤 실행 시점에 상태를 다시 읽으면 사용자가 동의하지 않은 원격을 덮어쓸 수 있다.
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)
        state.pushBranch(feature())
        gateway.pushResult = PushResult.Accepted

        state.confirmForcePush()

        gateway.lastPushRef shouldBe FEATURE
        gateway.lastPushRemote shouldBe REMOTE
        gateway.lastPushForce shouldBe true
    }

    test("지목 올리기가 non-fast-forward 로 거절되면 덮어쓰지 않고 그 브랜치의 확인을 띄운다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)

        state.pushBranch(feature())

        state.outcome shouldBe RemoteOperationOutcome.PushRejected(PushResult.RejectReason.NON_FAST_FORWARD)
        // 확인 전에는 덮어쓰지 않는다 — 거절 한 번으로 끝이고 재시도가 자동으로 나가지 않는다.
        gateway.lastPushForce shouldBe false
        gateway.pushCalls shouldBe 1
        state.forcePushPrompt shouldBe ForcePushPrompt(FEATURE, REMOTE, ForcePushTarget.NAMED)
    }

    test("거절 뒤 확인을 누르면 그 브랜치로만 덮어쓴다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)
        state.pushBranch(feature())

        gateway.pushResult = PushResult.Accepted
        state.confirmForcePush()

        gateway.pushCalls shouldBe 2
        gateway.lastPushRef shouldBe FEATURE
        gateway.lastPushForce shouldBe true
        state.outcome shouldBe RemoteOperationOutcome.BranchPushed(FEATURE, force = true)
        state.forcePushPrompt.shouldBeNull()
    }

    test("거절 뒤 확인을 물리면 덮어쓰기가 나가지 않는다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)
        state.pushBranch(feature())

        state.dismissForcePush()

        gateway.pushCalls shouldBe 1
        gateway.lastPushForce shouldBe false
        state.forcePushPrompt.shouldBeNull()
    }

    test("덮어쓰기가 다시 거절되면 같은 확인을 반복해 띄우지 않는다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
        val state = toolbarStateWith(gateway)
        state.pushBranch(feature())

        state.confirmForcePush()

        gateway.pushCalls shouldBe 2
        gateway.lastPushForce shouldBe true
        state.forcePushPrompt.shouldBeNull()
    }

    test("원격이 거절한 것이 non-fast-forward 가 아니면 덮어쓰기 확인을 띄우지 않는다") {
        val gateway = FakeRemoteGateway()
        gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.REMOTE_REJECTED)
        val state = toolbarStateWith(gateway)

        state.pushBranch(feature())

        state.forcePushPrompt.shouldBeNull()
    }

    test("툴바에서 띄운 확인은 현재 브랜치를 대상으로 한다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway)

        state.requestForcePush()

        state.forcePushPrompt shouldBe ForcePushPrompt(BRANCH_REF, REMOTE, ForcePushTarget.CURRENT)

        state.confirmForcePush()

        gateway.lastPushRef shouldBe BRANCH_REF
        gateway.lastPushForce shouldBe true
        state.outcome shouldBe RemoteOperationOutcome.Pushed(force = true)
    }

    test("막힌 지목 올리기는 눌려도 원격을 두드리지 않는다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway, remotes = emptyList())

        state.pushBranch(feature())

        gateway.pushCalls shouldBe 0
        state.outcome.shouldBeNull()
    }

    test("지목 받기는 그 브랜치의 추적 원격으로 fetch 하고 빨리 감기 결과를 남긴다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway, refGateway = fastForwardableRefGateway())

        state.pullBranch(feature())

        gateway.fetchCalls shouldBe 1
        gateway.lastRemote shouldBe REMOTE
        // 툴바의 원격 단위 pull 로 위임하지 않는다 (결정 D8).
        gateway.pullCalls shouldBe 0
        state.outcome.shouldBeInstanceOf<RemoteOperationOutcome.BranchPulled>()
            .outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
    }

    test("빨리 감기 성공은 이력 기록 실패 사유를 남기지 않는다") {
        val state = toolbarStateWith(FakeRemoteGateway(), refGateway = fastForwardableRefGateway())

        state.pullBranch(feature())

        state.undoRecordFailure.shouldBeNull()
    }

    test("옮겨 놓고 이력 기록만 실패하면 그 사유가 결과와 함께 화면까지 올라온다") {
        val recorder = spyRecorderOf()
        coEvery { recorder.record(any(), any(), any(), any()) } throws RECORD_FAILED
        val state = toolbarStateWith(
            FakeRemoteGateway(),
            refGateway = fastForwardableRefGateway(),
            recorder = recorder,
        )

        state.pullBranch(feature())

        // 이동은 끝났다 — 기록 실패를 조작 실패로 승격하지 않는다.
        state.outcome.shouldBeInstanceOf<RemoteOperationOutcome.BranchPulled>()
            .outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>()
            .undoRecordFailure shouldBe RECORD_FAILED
        // 상태에도 남긴다 — 여기서 끊기면 되돌릴 수 없다는 사실이 화면에 닿지 않는다 (결정 D16).
        state.undoRecordFailure shouldBe RECORD_FAILED
    }

    test("현재 체크아웃된 브랜치의 지목 받기는 눌려도 아무 원격 작업을 시작하지 않는다") {
        val gateway = FakeRemoteGateway()
        val state = toolbarStateWith(gateway)

        state.pullBranch(feature(isCurrent = true))

        gateway.fetchCalls shouldBe 0
        gateway.pullCalls shouldBe 0
        state.outcome.shouldBeNull()
    }

    test("빨리 감기가 아니면 거부 사유가 결과로 남고 실패로 접히지 않는다") {
        val refGateway = fastForwardableRefGateway()
        coEvery { refGateway.isDescendantOf(REMOTE_TARGET, LOCAL_TARGET) } returns false
        val state = toolbarStateWith(FakeRemoteGateway(), refGateway = refGateway)

        state.pullBranch(feature())

        state.outcome shouldBe RemoteOperationOutcome.BranchPulled(
            FastForwardOutcome.Refused(FEATURE, FastForwardRefusal.NOT_FAST_FORWARD),
        )
    }

    test("지목 받기의 인증 실패는 네트워크 실패와 구분되어 보고된다") {
        val gateway = FakeRemoteGateway()
        gateway.failure = UndineException.AuthenticationFailed(REMOTE)
        val state = toolbarStateWith(gateway, refGateway = fastForwardableRefGateway())

        state.pullBranch(feature())

        state.outcome shouldBe RemoteOperationOutcome.Failed(
            RemoteOperation.PULL,
            RemoteFailureKind.AUTHENTICATION,
        )
    }

    test("지목 올리기의 전송 실패는 인증 실패와 다른 종류로 보고된다") {
        val gateway = FakeRemoteGateway()
        gateway.failure = UndineException.GitOperationFailed("remote.push")
        val state = toolbarStateWith(gateway)

        state.pushBranch(feature())

        state.outcome shouldBe RemoteOperationOutcome.Failed(
            RemoteOperation.PUSH,
            RemoteFailureKind.UNEXPECTED,
        )
    }
})
