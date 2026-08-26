package dev.undine.application.worktree

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
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

private val HEAD = CommitId.of("b".repeat(40))

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

class WorktreeUseCasesSpec : BehaviorSpec({
    given("worktree Gateway") {
        val gateway = mockk<WorktreeGateway>(relaxUnitFun = true)
        val linked = Worktree("feature", RepositoryPath("/repo-feature"), RefName("feature"), WorktreeState.LINKED)
        val listing = WorktreeListing(listOf(linked), emptyList())
        coEvery { gateway.list() } returns listing
        coEvery { gateway.add(RepositoryPath("/repo-feature"), RefName("feature")) } returns linked

        `when`("목록 조회와 추가를 요청하면") {
            val stack = UndoStack()
            LoadWorktreesUseCase(gateway).execute() shouldBe listing
            AddWorktreeUseCase(gateway, recorderOn(stack))
                .execute(RepositoryPath("/repo-feature"), RefName("feature")) shouldBe linked

            then("결과를 바꾸지 않고 Gateway에 위임한다") {
                coVerify(exactly = 1) { gateway.list() }
                coVerify(exactly = 1) { gateway.add(RepositoryPath("/repo-feature"), RefName("feature")) }
            }

            then("추가만 Undo 스택에 남고 조회는 남지 않는다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_ADD)
                stack.peek()?.irreversibleReason.shouldNotBeNull()
            }
        }

        `when`("제거가 성공하면") {
            val stack = UndoStack()
            val removable = mockk<WorktreeGateway>(relaxUnitFun = true)
            RemoveWorktreeUseCase(removable, recorderOn(stack)).execute("feature")

            then("제거 종류를 되돌릴 수 없다는 사유와 함께 남긴다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_REMOVE)
                stack.peek()?.irreversibleReason.shouldNotBeNull()
            }
        }

        `when`("추가가 성공한 직후 호출자가 취소되면") {
            val stack = UndoStack()
            val changing = mockk<WorktreeGateway>()

            then("이미 만들어진 worktree 를 기록 없이 남기지 않는다 — 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { changing.add(RepositoryPath("/repo-feature"), RefName("feature")) } coAnswers {
                        callerJob.cancel()
                        linked
                    }
                    AddWorktreeUseCase(changing, recorderOn(stack, suspendingRefGateway()))
                        .execute(RepositoryPath("/repo-feature"), RefName("feature"))
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_ADD)
                stack.peek()?.irreversibleReason.shouldNotBeNull()
            }
        }

        `when`("제거·고아 prune 이 성공한 직후 호출자가 취소되면") {
            val stack = UndoStack()
            val changing = mockk<WorktreeGateway>()

            then("제거 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { changing.remove("feature") } coAnswers { callerJob.cancel() }
                    RemoveWorktreeUseCase(changing, recorderOn(stack, suspendingRefGateway())).execute("feature")
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_REMOVE)
            }
        }

        `when`("호출자가 이미 취소된 뒤에 실행하면") {
            val stack = UndoStack()
            val untouched = mockk<WorktreeGateway>(relaxUnitFun = true)

            then("아직 아무것도 바뀌지 않았으므로 제거를 시작하지 않는다") {
                afterCallerCancelled {
                    RemoveWorktreeUseCase(untouched, recorderOn(stack)).execute("feature")
                }

                coVerify(exactly = 0) { untouched.remove(any()) }
                stack.size shouldBe 0
            }
        }

        `when`("더티 worktree 제거를 요청하면") {
            val stack = UndoStack()
            coEvery { gateway.remove("feature") } throws UndineException.DirtyWorkingTree(listOf("a.kt", "b.kt"))

            then("성공이나 빈 목록으로 바꾸지 않고 실패를 전달하며 기록도 남기지 않는다") {
                shouldThrow<UndineException.DirtyWorkingTree> {
                    RemoveWorktreeUseCase(gateway, recorderOn(stack)).execute("feature")
                }
                stack.size shouldBe 0
            }
        }
    }
})
