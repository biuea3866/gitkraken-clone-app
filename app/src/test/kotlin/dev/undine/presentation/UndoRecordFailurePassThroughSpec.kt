package dev.undine.presentation

import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.RebaseTarget
import dev.undine.presentation.conflict.RecordingConflictGateway
import dev.undine.presentation.conflict.conflictStateWith
import dev.undine.presentation.rebase.RecordingRebaseGateway
import dev.undine.presentation.rebase.UPSTREAM
import dev.undine.presentation.rebase.rebaseStateWith
import dev.undine.presentation.sidebar.SAMPLE_FEATURE
import dev.undine.presentation.sidebar.SidebarStateHarness
import dev.undine.presentation.staging.FakeRepositoryGateway
import dev.undine.presentation.staging.RecordingStagingGateway
import dev.undine.presentation.staging.stagingStateWith
import dev.undine.presentation.staging.statusOf
import dev.undine.presentation.toolbar.FakeRemoteGateway
import dev.undine.presentation.toolbar.toolbarStateWith
import dev.undine.testsupport.commit
import dev.undine.testsupport.spyRecorderOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery

private val RECORD_FAILED = UndineException.GitOperationFailed("undo.record")

/** 커밋할 것이 올라가 있는 워킹트리. 커밋 버튼이 막히지 않아야 기록 경로까지 닿는다. */
private fun stagedRepository() = FakeRepositoryGateway(statusOf(staged = listOf("a.txt")))

/** 기록만 실패시키는 대역. Git 변경은 그대로 성공한다 — 그 경계가 이 스펙의 대상이다. */
private fun failingRecorder() = spyRecorderOf().also {
    coEvery { it.record(any(), any(), any(), any()) } throws RECORD_FAILED
    coEvery { it.recordIrreversible(any(), any(), any()) } throws RECORD_FAILED
}

/**
 * 기록 실패 사유가 **상태 홀더까지 전달되는지** 본다 (결정 G30 3).
 *
 * 이 티켓은 문구를 그리지 않는다 — wave 8 의 네 경로도 아직 그리지 않으므로 여섯 개만 그리면
 * 오히려 어긋난다. 여기서 보는 것은 "값이 화면 층에 닿는가" 까지이고, 실패 안내 렌더링은 후속
 * 티켓이 한 번에 처리한다. Compose 런타임 테스트는 추가하지 않는다 — 순수 상태 검증이다.
 */
class UndoRecordFailurePassThroughSpec : FunSpec({

    test("커밋 기록만 실패하면 스테이징 화면이 사유를 들고 있는다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(stagedRepository(), staging, failingRecorder())
        state.refresh()
        state.changeMessage("메모")

        state.commit()

        // 커밋 자체는 성공이므로 실패 안내가 아니라 기록 사유만 남는다.
        state.failure shouldBe null
        state.undoRecordFailure shouldBe RECORD_FAILED
        staging.commitMessages shouldBe listOf("메모")
    }

    test("체크아웃 기록만 실패하면 사이드바가 사유를 들고 있는다") {
        val harness = SidebarStateHarness(recorder = failingRecorder())
        val state = harness.loaded()

        state.checkout(SAMPLE_FEATURE)

        state.actionFailure shouldBe null
        state.undoRecordFailure shouldBe RECORD_FAILED
    }

    test("이어가기 기록만 실패하면 충돌 화면이 사유를 들고 있는다") {
        val state = conflictStateWith(
            conflict = RecordingConflictGateway(emptyList(), emptyMap()),
            recorder = failingRecorder(),
        )

        state.continueOperation()

        state.undoRecordFailure shouldBe RECORD_FAILED
    }

    test("계획 적용 기록만 실패하면 리베이스 화면이 사유를 들고 있는다") {
        val gateway = RecordingRebaseGateway(targets = listOf(RebaseTarget(commit(3), isPushed = false)))
        val state = rebaseStateWith(gateway, failingRecorder())
        state.load()

        state.apply()

        state.failure shouldBe null
        state.undoRecordFailure shouldBe RECORD_FAILED
    }

    test("push 기록만 실패하면 툴바가 사유를 들고 있는다") {
        val state = toolbarStateWith(FakeRemoteGateway(), recorder = failingRecorder())

        state.push()

        state.undoRecordFailure shouldBe RECORD_FAILED
    }

    test("기록이 성공하면 사유는 비어 있다 — 화면이 숨길 실패가 없다") {
        val staging = RecordingStagingGateway()
        val state = stagingStateWith(stagedRepository(), staging)
        state.refresh()
        state.changeMessage("메모")

        state.commit()

        state.undoRecordFailure shouldBe null
    }

    test("되돌리기 대상 이름은 화면이 그대로 보여줄 수 있는 값이다") {
        RefName("feature/login").value shouldBe "feature/login"
    }
})
