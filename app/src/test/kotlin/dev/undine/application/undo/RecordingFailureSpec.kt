package dev.undine.application.undo

import dev.undine.domain.CheckoutResult
import dev.undine.domain.CommitResult
import dev.undine.domain.PushResult
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.undo.GitOperationKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val RECORD_FAILED = UndineException.GitOperationFailed("undo.record")

/**
 * 시작한 세션이 락 대기 중 닫혀 `GitAccess` 가 실행을 거부한 실패 (UND-80).
 * 사유 문장은 infrastructure 가 소유하므로 여기서는 **도메인 예외 종류**만 본다.
 */
private val SESSION_CLOSED = UndineException.StateViolation("작업을 시작한 저장소가 닫혀 실행하지 않았습니다")

/** [block] 을 실행하되 Git 변경이 성공하는 순간 호출자를 취소한다. 취소됐으면 true. */
private suspend fun cancellingCallerOnChange(block: suspend (Job) -> Unit): Boolean {
    val callerJob = Job()
    val job = CoroutineScope(Dispatchers.Default + callerJob).launch { block(callerJob) }
    job.join()
    return callerJob.isCancelled
}

/** 이미 취소된 호출자 안에서 [block] 을 실행한다. */
private suspend fun afterCallerCancelled(block: suspend () -> Unit) {
    val callerJob = Job()
    val gate = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val job = CoroutineScope(Dispatchers.Default + callerJob).launch {
        entered.complete(Unit)
        runCatching { gate.await() }
        block()
    }
    entered.await()
    callerJob.cancel()
    job.join()
}

/**
 * 기록 실패와 취소를 어떻게 다루는지 본다.
 *
 * 세 가지를 지켜야 한다 (`.agent/rules/exception-handling.md` 규칙 5·8, 결정 A-L2·G30).
 * 1. Git 변경이 이미 적용된 뒤의 기록 실패는 **변경 실패로 승격되지 않고** 결과에 실린다.
 * 2. `CancellationException` 은 기록 실패로 강등하지 않고 **다시 던진다**.
 * 3. 변경이 성공한 뒤 호출자가 취소돼도 기록은 **정확히 한 건** 남는다.
 */
class RecordingFailureSpec : BehaviorSpec({

    given("시작한 저장소가 바뀌어 Gateway 가 실행 자체를 거부한 상황") {
        `when`("커밋 Gateway 가 세션 종료 사유로 실패하면") {
            val harness = RecordingHarness()
            coEvery { harness.staging.commit(any()) } throws SESSION_CLOSED

            then("저장소를 바꾸지 않았으므로 기록 단계에 닿지 않는다") {
                shouldThrow<UndineException.StateViolation> {
                    harness.commitStaged.execute("메시지")
                }.detail shouldBe SESSION_CLOSED.detail

                coVerify(exactly = 0) { harness.recorder.record(any(), any(), any(), any()) }
                coVerify(exactly = 0) { harness.recorder.recordIrreversible(any(), any(), any()) }
                harness.stack.history().shouldBeEmpty()
            }
        }
    }

    given("Git 변경 뒤 기록만 실패하는 상황") {
        `when`("커밋 기록이 UndineException 으로 실패하면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.record(any(), any(), any(), any()) } throws RECORD_FAILED

            then("커밋은 성공으로 돌려주되 사유를 결과에 싣는다") {
                val outcome = harness.commitStaged.execute("메시지")

                outcome.result.commitId shouldBe AFTER_CHANGE
                outcome.undoRecordFailure
                    .shouldBeInstanceOf<UndineException.GitOperationFailed>().operation shouldBe "undo.record"
            }
        }

        `when`("체크아웃 기록이 실패하면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.record(any(), any(), any(), any()) } throws RECORD_FAILED

            then("체크아웃 결과에 사유를 실어 돌려준다") {
                harness.checkoutBranch(RECORDED_TARGET).undoRecordFailure shouldBe RECORD_FAILED
            }
        }

        `when`("push 의 복구 불가 기록이 실패하면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.recordIrreversible(any(), any(), any()) } throws RECORD_FAILED

            then("이미 원격에 올라간 사실을 실패로 뒤집지 않는다") {
                val outcome = harness.pushRemote.execute(RECORDED_BRANCH, force = false) { }

                outcome.result shouldBe PushResult.Accepted
                outcome.undoRecordFailure shouldBe RECORD_FAILED
            }
        }

        `when`("병합·리베이스·cherry-pick 기록이 실패하면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.record(any(), any(), any(), any()) } throws RECORD_FAILED

            then("세 경로 모두 변경 결과를 유지하고 사유만 싣는다") {
                harness.mergeBranch.execute(RECORDED_TARGET).undoRecordFailure shouldBe RECORD_FAILED
                harness.rebaseBranch.execute(RECORDED_TARGET).undoRecordFailure shouldBe RECORD_FAILED
                harness.cherryPickCommits.execute(listOf(AFTER_CHANGE)).undoRecordFailure shouldBe RECORD_FAILED
                harness.applyRebasePlan.execute(RECORDED_BRANCH, singleStepPlan())
                    .undoRecordFailure shouldBe RECORD_FAILED
                val continuing = continuingCherryPickHarness()
                coEvery { continuing.recorder.record(any(), any(), any(), any()) } throws RECORD_FAILED
                continuing.continueCherryPick.execute().undoRecordFailure shouldBe RECORD_FAILED
            }
        }
    }

    given("기록 도중 취소가 오는 상황") {
        `when`("커밋 기록이 취소로 끝나면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.record(any(), any(), any(), any()) } throws CancellationException("기록 취소")

            then("취소를 기록 실패로 강등하지 않고 그대로 올린다") {
                shouldThrow<CancellationException> { harness.commitStaged.execute("메시지") }
            }
        }

        `when`("push 의 복구 불가 기록이 취소로 끝나면") {
            val harness = RecordingHarness()
            coEvery { harness.recorder.recordIrreversible(any(), any(), any()) } throws
                CancellationException("기록 취소")

            then("취소를 삼키지 않는다") {
                shouldThrow<CancellationException> {
                    harness.pushRemote.execute(RECORDED_BRANCH, force = false) { }
                }
            }
        }

        `when`("cherry-pick 이어가기 기록이 취소로 끝나면") {
            val harness = continuingCherryPickHarness()
            coEvery { harness.recorder.record(any(), any(), any(), any()) } throws
                CancellationException("기록 취소")

            then("취소를 삼키지 않는다") {
                shouldThrow<CancellationException> { harness.continueCherryPick.execute() }
            }
        }
    }

    given("변경이 성공한 직후 호출자가 취소되는 상황") {
        RECORDING_PATHS.forEach { path ->
            `when`("${path.name} 이(가) 끝나는 순간 취소되면") {
                then("${path.operation.name} 기록이 정확히 한 건 남는다") {
                    val harness = path.harness()

                    val cancelled = cancellingCallerOnChange { callerJob ->
                        path.stubChange(harness) { callerJob.cancel() }
                        path.execute(harness)
                    }

                    cancelled shouldBe true
                    harness.stack.history().map { it.operation } shouldContainExactly listOf(path.operation)
                }
            }
        }
    }

    given("호출자가 이미 취소된 상황") {
        RECORDING_PATHS.forEach { path ->
            `when`("${path.name} 을(를) 시작하려 하면") {
                then("저장소를 바꾸지 않고 기록도 남기지 않는다") {
                    val harness = path.harness()

                    afterCallerCancelled { path.execute(harness) }

                    path.verifyNoChange(harness)
                    harness.stack.history().shouldBeEmpty()
                }
            }
        }
    }
})

/**
 * 취소 계약을 지켜야 하는 기록 경로 한 줄.
 *
 * 표로 두는 이유는 **경로마다 빠짐없이** 두 가지를 봐야 하기 때문이다 — 선행 취소면 저장소를 건드리지
 * 않고, 변경이 성공했으면 취소가 와도 기록이 정확히 한 건 남는다 (결정 A-L2). 경로별로 손으로 쓰면
 * 새 기록 경로가 늘 때 한둘이 조용히 빠진다.
 *
 * @property stubChange 변경 Gateway 가 결과를 돌려주는 **그 순간** 취소를 걸도록 대역을 다시 세운다.
 * @property verifyNoChange 변경 Gateway 를 부르지 않았는지 확인한다.
 */
private class RecordingPath(
    val name: String,
    val operation: GitOperationKind,
    val harness: () -> RecordingHarness,
    val stubChange: (RecordingHarness, () -> Unit) -> Unit,
    val verifyNoChange: (RecordingHarness) -> Unit,
    val execute: suspend (RecordingHarness) -> Unit,
)

private fun commitResult() = CommitResult(AFTER_CHANGE, previousHead = BEFORE_CHANGE, baseline = BASELINE_AFTER)

private fun mergeSucceeded() =
    MergeResult.Succeeded(AFTER_CHANGE, fastForward = false, previousHead = BEFORE_CHANGE, baseline = BASELINE_AFTER)

private fun rebaseSucceeded() = RebaseResult.Succeeded(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER)

private fun continuingHarness(state: RepositoryState): RecordingHarness =
    RecordingHarness(merge = mergeGatewayReturning(state = state))

private fun continuingCherryPickHarness(): RecordingHarness =
    RecordingHarness(cherryPick = cherryPickGatewayContinuing())

@Suppress("LongMethod") // 표 자체다 — 한 행이 한 기록 경로이고, 쪼개면 빠진 경로가 보이지 않는다.
private fun recordingPaths(): List<RecordingPath> = listOf(
    RecordingPath(
        name = "커밋",
        operation = GitOperationKind.COMMIT,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.staging.commit(any()) } coAnswers { onChange(); commitResult() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.staging.commit(any()) } },
        execute = { harness -> harness.commitStaged.execute("메시지") },
    ),
    RecordingPath(
        name = "amend",
        operation = GitOperationKind.COMMIT,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.staging.amend(any(), any()) } coAnswers { onChange(); commitResult() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.staging.amend(any(), any()) } },
        execute = { harness -> harness.amendCommit.request("고침") },
    ),
    RecordingPath(
        name = "체크아웃",
        operation = GitOperationKind.CHECKOUT,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.refs.checkout(any(), any()) } coAnswers {
                onChange()
                CheckoutResult(previousRef = RECORDED_BRANCH, baseline = BASELINE_AFTER)
            }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.refs.checkout(any(), any()) } },
        execute = { harness -> harness.checkoutBranch(RECORDED_TARGET) },
    ),
    RecordingPath(
        // push 만 전송이 취소 가능하다 — 취소 불가로 감싸는 것은 전송이 끝난 **뒤의 기록**뿐이다.
        name = "push",
        operation = GitOperationKind.PUSH,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.remote.push(any(), any(), any()) } coAnswers { onChange(); PushResult.Accepted }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.remote.push(any(), any(), any()) } },
        execute = { harness -> harness.pushRemote.execute(RECORDED_BRANCH, force = false) { } },
    ),
    RecordingPath(
        name = "병합",
        operation = GitOperationKind.MERGE,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.merge.merge(any(), any()) } coAnswers { onChange(); mergeSucceeded() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.merge.merge(any(), any()) } },
        execute = { harness -> harness.mergeBranch.execute(RECORDED_TARGET) },
    ),
    RecordingPath(
        name = "리베이스",
        operation = GitOperationKind.REBASE,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.merge.rebase(any()) } coAnswers { onChange(); rebaseSucceeded() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.merge.rebase(any()) } },
        execute = { harness -> harness.rebaseBranch.execute(RECORDED_TARGET) },
    ),
    RecordingPath(
        name = "충돌 해결 후 병합 계속",
        operation = GitOperationKind.MERGE,
        harness = { continuingHarness(RepositoryState.MERGING) },
        stubChange = { harness, onChange ->
            coEvery { harness.merge.continueMerge() } coAnswers { onChange(); mergeSucceeded() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.merge.continueMerge() } },
        execute = { harness -> harness.continueAfterResolve.execute(RepositoryState.MERGING) },
    ),
    RecordingPath(
        name = "충돌 해결 후 리베이스 계속",
        operation = GitOperationKind.REBASE,
        harness = { continuingHarness(RepositoryState.REBASING) },
        stubChange = { harness, onChange ->
            coEvery { harness.merge.continueRebase() } coAnswers { onChange(); rebaseSucceeded() }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.merge.continueRebase() } },
        execute = { harness -> harness.continueAfterResolve.execute(RepositoryState.REBASING) },
    ),
    RecordingPath(
        name = "대화형 리베이스 계획 적용",
        operation = GitOperationKind.REBASE,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.interactiveRebase.apply(any(), any()) } coAnswers {
                onChange()
                InteractiveRebaseOutcome.Completed(BEFORE_CHANGE, BASELINE_AFTER)
            }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.interactiveRebase.apply(any(), any()) } },
        execute = { harness -> harness.applyRebasePlan.execute(RECORDED_BRANCH, singleStepPlan()) },
    ),
    RecordingPath(
        name = "cherry-pick",
        operation = GitOperationKind.CHERRY_PICK,
        harness = { RecordingHarness() },
        stubChange = { harness, onChange ->
            coEvery { harness.cherryPick.apply(any(), any()) } coAnswers {
                onChange()
                CherryPickStep.Created(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER)
            }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.cherryPick.apply(any(), any()) } },
        execute = { harness -> harness.cherryPickCommits.execute(listOf(AFTER_CHANGE)) },
    ),
    RecordingPath(
        name = "cherry-pick 이어가기",
        operation = GitOperationKind.CHERRY_PICK,
        harness = ::continuingCherryPickHarness,
        stubChange = { harness, onChange ->
            coEvery { harness.cherryPick.continueAfterResolve() } coAnswers {
                onChange()
                CherryPickStep.Created(AFTER_CHANGE, BEFORE_CHANGE, BASELINE_AFTER)
            }
        },
        verifyNoChange = { harness -> coVerify(exactly = 0) { harness.cherryPick.continueAfterResolve() } },
        execute = { harness -> harness.continueCherryPick.execute() },
    ),
)

private val RECORDING_PATHS: List<RecordingPath> = recordingPaths()
