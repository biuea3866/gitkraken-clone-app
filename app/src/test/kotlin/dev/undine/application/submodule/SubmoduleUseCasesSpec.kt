package dev.undine.application.submodule

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStack
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val HEAD = CommitId.of("a".repeat(40))

private fun branches() = listOf(
    Branch(RefName("main"), HEAD, isCurrent = true, isRemote = false, upstream = null, ahead = 0, behind = 0),
)

private fun recorderOn(stack: UndoStack, refGateway: RefGateway = readableRefGateway()): OperationRecorder =
    OperationRecorder(refGateway, stack)

private fun readableRefGateway(): RefGateway = mockk<RefGateway>().also {
    coEvery { it.listBranches() } returns branches()
}

/**
 * 기록 경로에 **실제 취소 지점**을 만든 RefGateway.
 *
 * 모킹된 조회는 중단하지 않으므로 그냥 두면 취소가 끼어들 자리가 없어, 기록이 취소로부터 보호되는지를
 * 이 테스트가 검증하지 못한다. `yield()` 로 중단점을 하나 넣어 보호가 실제로 필요하게 만든다.
 */
private fun suspendingRefGateway(): RefGateway = mockk<RefGateway>().also {
    coEvery { it.listBranches() } coAnswers {
        yield()
        branches()
    }
}

/** [block] 을 실행하되, 그 안의 Git 변경이 성공하는 순간 호출자를 취소한다. 취소됐으면 true. */
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
        runCatching { gate.await() } // 취소를 여기서 받아 삼키고, 취소된 채로 UseCase 에 들어간다
        block()
    }
    entered.await()
    callerJob.cancel()
    job.join()
}

class SubmoduleUseCasesSpec : BehaviorSpec({
    given("서브모듈 Gateway") {
        val gateway = mockk<SubmoduleGateway>(relaxUnitFun = true)
        val submodule = Submodule("modules/core", null, SubmoduleState(true, false, false))
        coEvery { gateway.list() } returns listOf(submodule)

        `when`("목록·초기화·업데이트 UseCase를 실행하면") {
            val stack = UndoStack()
            val recorder = recorderOn(stack)
            val listed = LoadSubmodulesUseCase(gateway).execute()
            InitializeSubmoduleUseCase(gateway, recorder).execute("modules/core")
            UpdateSubmoduleUseCase(gateway, recorder).execute("modules/core")

            then("각 요청을 Gateway에 그대로 위임한다") {
                listed shouldBe listOf(submodule)
                coVerify(exactly = 1) { gateway.initialize("modules/core", recursive = false) }
                coVerify(exactly = 1) { gateway.update("modules/core", recursive = false) }
            }

            then("변경 연산만 Undo 스택에 종류와 함께 남는다") {
                stack.history().map { it.operation } shouldContainExactly listOf(
                    GitOperationKind.SUBMODULE_UPDATE,
                    GitOperationKind.SUBMODULE_INIT,
                )
                stack.history().forEach { it.irreversibleReason.shouldNotBeNull() }
            }
        }

        `when`("초기화가 성공한 직후 호출자가 취소되면") {
            val stack = UndoStack()
            val changing = mockk<SubmoduleGateway>()

            then("이미 바뀐 Git 을 기록 없이 남기지 않는다 — 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { changing.initialize("modules/core", recursive = false) } coAnswers { callerJob.cancel() }
                    InitializeSubmoduleUseCase(changing, recorderOn(stack, suspendingRefGateway()))
                        .execute("modules/core")
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.SUBMODULE_INIT)
                stack.peek()?.irreversibleReason.shouldNotBeNull()
            }
        }

        `when`("업데이트가 성공한 직후 호출자가 취소되면") {
            val stack = UndoStack()
            val changing = mockk<SubmoduleGateway>()

            then("업데이트 기록도 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { changing.update("modules/core", recursive = false) } coAnswers { callerJob.cancel() }
                    UpdateSubmoduleUseCase(changing, recorderOn(stack, suspendingRefGateway()))
                        .execute("modules/core")
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.SUBMODULE_UPDATE)
            }
        }

        `when`("호출자가 이미 취소된 뒤에 실행하면") {
            val stack = UndoStack()
            val untouched = mockk<SubmoduleGateway>(relaxUnitFun = true)

            then("아직 아무것도 바뀌지 않았으므로 변경을 시작하지 않는다") {
                afterCallerCancelled {
                    InitializeSubmoduleUseCase(untouched, recorderOn(stack)).execute("modules/core")
                }

                coVerify(exactly = 0) { untouched.initialize(any(), any()) }
                stack.size shouldBe 0
            }
        }

        `when`("초기화가 실패하면") {
            val stack = UndoStack()
            val failing = mockk<SubmoduleGateway>()
            coEvery {
                failing.initialize("modules/core", recursive = false)
            } throws UndineException.GitOperationFailed("submodule.init")

            then("하지 않은 일을 되돌리지 않도록 기록도 남기지 않는다") {
                shouldThrow<UndineException.GitOperationFailed> {
                    InitializeSubmoduleUseCase(failing, recorderOn(stack)).execute("modules/core")
                }
                stack.size shouldBe 0
            }
        }
    }

    given("스테이징 Gateway") {
        val gateway = mockk<StagingGateway>(relaxUnitFun = true)
        val result = committed()
        coEvery { gateway.stageAndCommit(listOf("modules/core"), "서브모듈 포인터 갱신") } returns result

        `when`("현재 서브모듈 상태를 부모에 커밋하면") {
            val committed = CommitSubmodulePointerUseCase(gateway).execute(
                path = "modules/core",
                message = "서브모듈 포인터 갱신",
            )

            then("stage 와 commit 을 나눠 부르지 않고 결합 연산 하나로 끝낸다") {
                committed shouldBe result
                coVerify(exactly = 1) { gateway.stageAndCommit(listOf("modules/core"), "서브모듈 포인터 갱신") }
                // 나눠 부르면 그 사이의 취소가 gitlink 만 올라간 부분 상태를 남긴다 (UND-81).
                coVerify(exactly = 0) { gateway.stage(any()) }
                coVerify(exactly = 0) { gateway.commit(any()) }
            }
        }
    }
})

/** 커밋 결과가 싣는 되돌리기 재료 (UND-73). 이 스펙은 gitlink 커밋 경로만 본다. */
private fun committed(): CommitResult = CommitResult(
    CommitId.of("a".repeat(40)),
    previousHead = commitId(9),
    baseline = baselineOf(CommitId.of("a".repeat(40))),
)
