package dev.undine.application.graphops

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.BranchOperation
import dev.undine.domain.BranchOperationResult
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.Tag
import dev.undine.domain.UndineException
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val RELEASE_TAG = RefName("v1.0.0")

private val MAIN_HEAD = CommitId.of("a".repeat(40))
private val FEATURE_HEAD = CommitId.of("b".repeat(40))
private val OLD_COMMIT = CommitId.of("c".repeat(40))
private val NEW_HEAD = CommitId.of("d".repeat(40))

private fun branch(name: RefName, target: CommitId, isCurrent: Boolean = false) =
    Branch(name, target, isCurrent = isCurrent, isRemote = false, upstream = null, ahead = 0, behind = 0)

private fun tag(target: CommitId) = Tag(RELEASE_TAG, target, isAnnotated = false, message = null, tagger = null)

/** 브랜치·태그를 읽을 수 있는 RefGateway. 기록의 기준 상태 조회도 같은 목을 쓴다. */
private fun refGateway(
    branches: List<Branch> = listOf(branch(MAIN, MAIN_HEAD, isCurrent = true), branch(FEATURE, FEATURE_HEAD)),
    tags: List<Tag> = listOf(tag(OLD_COMMIT)),
): RefGateway = mockk<RefGateway>(relaxUnitFun = true).also {
    coEvery { it.listBranches() } returns branches
    coEvery { it.listTags() } returns tags
}

/** 조회에 **실제 중단점**을 넣은 RefGateway. 없으면 취소가 끼어들 자리가 없어 보호를 검증하지 못한다. */
private fun suspendingRefGateway(): RefGateway = mockk<RefGateway>(relaxUnitFun = true).also {
    coEvery { it.listBranches() } coAnswers {
        yield()
        listOf(branch(MAIN, MAIN_HEAD, isCurrent = true), branch(FEATURE, FEATURE_HEAD))
    }
    coEvery { it.listTags() } coAnswers {
        yield()
        listOf(tag(OLD_COMMIT))
    }
}

private fun succeedingOps(
    result: BranchOperationResult = BranchOperationResult.Succeeded(MAIN, MAIN_HEAD, NEW_HEAD),
): WorktreeOpsGateway = mockk<WorktreeOpsGateway>(relaxUnitFun = true).also {
    coEvery { it.runOnBranch(any(), any()) } returns result
}

private fun useCase(
    worktreeOps: WorktreeOpsGateway,
    refs: RefGateway,
    stack: UndoStack,
): ExecuteGraphOperationUseCase =
    ExecuteGraphOperationUseCase(worktreeOps, refs, OperationRecorder(refs, stack))

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

private fun strategiesIn(stack: UndoStack): List<UndoStrategy> = stack.history().map { it.strategy }

/**
 * 조작 직전 조회는 성공하고 **기록 시점의 기준 상태 조회만** [failure] 로 실패하는 RefGateway.
 * 기록은 Git 변경이 이미 적용된 뒤에 일어나므로, 이 경계가 곧 "바뀌었는데 기록만 없는" 경우다.
 */
/**
 * Undo 기록만 실패하게 만든다. `OperationRecorder` 가 기준 상태를 읽으려 `listBranches()` 를 부르므로
 * 그 호출을 실패시키면 **Git 변경은 성공한 뒤 기록만 실패한** 상태가 된다.
 *
 * 조작 자체는 `WorktreeOpsGateway` 가 수행하고 이전 위치는 결과의 `previousTarget` 으로 오므로,
 * UseCase 는 조작 경로에서 `RefGateway` 를 읽지 않는다 (UND-72).
 */
private fun refGatewayFailingOnRead(failure: Throwable): RefGateway =
    mockk<RefGateway>(relaxUnitFun = true).also {
        coEvery { it.listBranches() } throws failure
    }

class GraphOperationUseCasesSpec : BehaviorSpec({

    given("브랜치→브랜치 병합") {
        `when`("실행하면") {
            val stack = UndoStack()
            val worktreeOps = succeedingOps()
            val refs = refGateway()
            val outcome = useCase(worktreeOps, refs, stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("드롭 대상 브랜치에서 fast-forward 를 허용해 실행한다") {
                coVerify(exactly = 1) {
                    worktreeOps.runOnBranch(
                        BranchTarget.Named(MAIN),
                        BranchOperation.Merge(FEATURE, allowFastForward = true),
                    )
                }
            }

            then("성공한 브랜치를 이전 위치로 되돌리는 전략을 기록한다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.MERGE)
                strategiesIn(stack) shouldContainExactly listOf(
                    UndoStrategy.HardResetTo(branch = MAIN, previous = MAIN_HEAD, expected = NEW_HEAD),
                )
            }

            then("결과는 수행 브랜치와 새 head 를 그대로 전한다") {
                val completed = outcome.shouldBeInstanceOf<GraphOperationOutcome.Completed>()
                completed.ref shouldBe MAIN
                completed.head shouldBe NEW_HEAD
                completed.undoRecordFailure.shouldBeNull()
            }
        }
    }

    given("브랜치→브랜치 리베이스") {
        `when`("실행하면") {
            val stack = UndoStack()
            val worktreeOps = succeedingOps(BranchOperationResult.Succeeded(FEATURE, FEATURE_HEAD, NEW_HEAD))
            val refs = refGateway()
            useCase(worktreeOps, refs, stack)
                .execute(GraphOperation.Rebase(branch = BranchTarget.Named(FEATURE), upstream = MAIN))

            then("드래그 소스 브랜치에서 실행한다 — 병합과 수행 브랜치가 반대다") {
                coVerify(exactly = 1) {
                    worktreeOps.runOnBranch(BranchTarget.Named(FEATURE), BranchOperation.Rebase(MAIN))
                }
            }

            then("리베이스 종류로 기록하고 되돌릴 대상은 재배치된 브랜치다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.REBASE)
                strategiesIn(stack) shouldContainExactly listOf(
                    UndoStrategy.HardResetTo(branch = FEATURE, previous = FEATURE_HEAD, expected = NEW_HEAD),
                )
            }
        }
    }

    given("커밋→브랜치 cherry-pick") {
        `when`("실행하면") {
            val stack = UndoStack()
            val worktreeOps = succeedingOps()
            useCase(worktreeOps, refGateway(), stack)
                .execute(GraphOperation.CherryPick(commit = OLD_COMMIT, onto = BranchTarget.Named(MAIN)))

            then("대상 브랜치에서 그 커밋을 적용한다") {
                coVerify(exactly = 1) {
                    worktreeOps.runOnBranch(
                        BranchTarget.Named(MAIN),
                        BranchOperation.CherryPick(OLD_COMMIT, recordOrigin = false),
                    )
                }
            }

            then("cherry-pick 종류로 기록한다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.CHERRY_PICK)
            }
        }
    }

    given("현재 브랜치에서 실행해야 하는 명령 경로") {
        `when`("BranchTarget.Current 로 요청하면") {
            val stack = UndoStack()
            val worktreeOps = succeedingOps()
            useCase(worktreeOps, refGateway(), stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Current))

            then("현재 브랜치를 미리 풀지 않고 Gateway 에 그대로 위임한다") {
                coVerify(exactly = 1) { worktreeOps.runOnBranch(BranchTarget.Current, any()) }
                coVerify(exactly = 0) { worktreeOps.runOnBranch(match { it is BranchTarget.Named }, any()) }
            }

            then("무엇에 가해졌는지는 결과가 말한 브랜치로 기록한다") {
                strategiesIn(stack) shouldContainExactly listOf(
                    UndoStrategy.HardResetTo(branch = MAIN, previous = MAIN_HEAD, expected = NEW_HEAD),
                )
            }
        }
    }

    given("브랜치→커밋 reset") {
        `when`("실행하면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>(relaxUnitFun = true)
            val refs = refGateway(branches = listOf(branch(MAIN, MAIN_HEAD, isCurrent = true)))
            val outcome = useCase(worktreeOps, refs, stack)
                .execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))

            then("hardResetBranch 로 실행하고 moveBranch 로 대체하지 않는다") {
                coVerify(exactly = 1) { worktreeOps.hardResetBranch(MAIN, OLD_COMMIT, MAIN_HEAD) }
                coVerify(exactly = 0) { refs.moveBranch(any(), any(), any()) }
            }

            then("브랜치 이동 종류로 기록하고 되돌리기도 조건부 갱신을 쓴다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.BRANCH_MOVE)
                strategiesIn(stack) shouldContainExactly listOf(
                    UndoStrategy.HardResetTo(branch = MAIN, previous = MAIN_HEAD, expected = OLD_COMMIT),
                )
            }

            then("결과는 옮겨진 위치를 전한다") {
                outcome.shouldBeInstanceOf<GraphOperationOutcome.Completed>().head shouldBe OLD_COMMIT
            }
        }

        `when`("드래그를 시작한 뒤 그 브랜치가 다른 곳으로 옮겨졌으면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>(relaxUnitFun = true)
            val moved = CommitId.of("e".repeat(40))
            val refs = refGateway(branches = listOf(branch(MAIN, moved, isCurrent = true)))
            useCase(worktreeOps, refs, stack).execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))

            then("화면 스냅샷이 아니라 조작 직전에 읽은 값을 expected 로 넘긴다") {
                coVerify(exactly = 1) { worktreeOps.hardResetBranch(MAIN, OLD_COMMIT, moved) }
            }
        }

        `when`("expected 와 실제 target 이 어긋나 Gateway 가 거부하면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>()
            coEvery { worktreeOps.hardResetBranch(any(), any(), any()) } throws
                UndineException.StateViolation("target mismatch")

            then("성공으로 숨기지 않고 실패를 전달하며 기록도 남기지 않는다") {
                shouldThrow<UndineException.StateViolation> {
                    useCase(worktreeOps, refGateway(), stack)
                        .execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))
                }
                stack.history().shouldBeEmpty()
            }
        }

        `when`("그 브랜치가 더 이상 없으면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>(relaxUnitFun = true)

            then("추측한 expected 로 실행하지 않고 없다고 알린다") {
                shouldThrow<UndineException.NotFound> {
                    useCase(worktreeOps, refGateway(branches = emptyList()), stack)
                        .execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))
                }
                coVerify(exactly = 0) { worktreeOps.hardResetBranch(any(), any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("태그→커밋 이동") {
        `when`("실행하면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>(relaxUnitFun = true)
            val refs = refGateway(tags = listOf(tag(OLD_COMMIT)))
            useCase(worktreeOps, refs, stack)
                .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))

            then("조작 직전에 읽은 값을 expected 로 넘겨 moveTag 로 실행한다") {
                coVerify(exactly = 1) { refs.moveTag(RELEASE_TAG, NEW_HEAD, OLD_COMMIT) }
            }

            then("태그 이동 종류로 기록하고 되돌리기는 이전·기대 target 을 둘 다 갖는다") {
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.TAG_MOVE)
                strategiesIn(stack) shouldContainExactly listOf(
                    UndoStrategy.MoveTagTo(tag = RELEASE_TAG, previous = OLD_COMMIT, expected = NEW_HEAD),
                )
            }
        }

        `when`("expected 와 실제 target 이 어긋나 Gateway 가 거부하면") {
            val stack = UndoStack()
            val refs = refGateway()
            coEvery { refs.moveTag(any(), any(), any()) } throws
                UndineException.StateViolation("tag target mismatch")

            then("성공으로 숨기지 않고 실패를 전달하며 기록도 남기지 않는다") {
                shouldThrow<UndineException.StateViolation> {
                    useCase(mockk<WorktreeOpsGateway>(relaxUnitFun = true), refs, stack)
                        .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))
                }
                stack.history().shouldBeEmpty()
            }
        }

        `when`("그 태그가 더 이상 없으면") {
            val stack = UndoStack()
            val refs = refGateway(tags = emptyList())

            then("추측한 expected 로 옮기지 않고 없다고 알린다") {
                shouldThrow<UndineException.NotFound> {
                    useCase(mockk<WorktreeOpsGateway>(relaxUnitFun = true), refs, stack)
                        .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))
                }
                coVerify(exactly = 0) { refs.moveTag(any(), any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("충돌로 멈춘 조작") {
        `when`("Gateway 가 Conflicted 를 돌려주면") {
            val stack = UndoStack()
            val conflicted = BranchOperationResult.Conflicted(MAIN, MAIN_HEAD, listOf("a.kt", "b.kt"))
            val outcome = useCase(succeedingOps(conflicted), refGateway(), stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("실패로 접지 않고 진행 중 충돌 상태를 그대로 올린다") {
                val result = outcome.shouldBeInstanceOf<GraphOperationOutcome.Conflicted>()
                result.ref shouldBe MAIN
                result.paths shouldContainExactly listOf("a.kt", "b.kt")
            }

            then("아직 끝나지 않았으므로 되돌리기 기록을 남기지 않는다") {
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("적용할 변경이 없던 조작") {
        `when`("Gateway 가 NoChange 를 돌려주면") {
            val stack = UndoStack()
            val outcome = useCase(succeedingOps(BranchOperationResult.NoChange(MAIN, MAIN_HEAD)), refGateway(), stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("바뀐 것이 없다고 그대로 알리고 되돌릴 항목을 만들지 않는다") {
                outcome.shouldBeInstanceOf<GraphOperationOutcome.NoChange>().ref shouldBe MAIN
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("Gateway 가 실패한 조작") {
        `when`("더티 워킹트리로 거부되면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>()
            coEvery { worktreeOps.runOnBranch(any(), any()) } throws
                UndineException.DirtyWorkingTree(listOf("a.kt"))

            then("빈 성공으로 바꾸지 않고 실패를 전달하며 기록도 남기지 않는다") {
                shouldThrow<UndineException.DirtyWorkingTree> {
                    useCase(worktreeOps, refGateway(), stack)
                        .execute(GraphOperation.Rebase(branch = BranchTarget.Named(FEATURE), upstream = MAIN))
                }
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("변경 직후의 취소") {
        `when`("병합이 성공한 순간 호출자가 취소되면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>()

            then("바뀐 저장소를 기록 없이 남기지 않는다 — 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { worktreeOps.runOnBranch(any(), any()) } coAnswers {
                        callerJob.cancel()
                        BranchOperationResult.Succeeded(MAIN, MAIN_HEAD, NEW_HEAD)
                    }
                    useCase(worktreeOps, suspendingRefGateway(), stack)
                        .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.MERGE)
            }
        }

        `when`("태그 이동이 성공한 순간 호출자가 취소되면") {
            val stack = UndoStack()
            val refs = suspendingRefGateway()
            coEvery { refs.moveTag(any(), any(), any()) } coAnswers { }

            then("이동 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { refs.moveTag(any(), any(), any()) } coAnswers { callerJob.cancel() }
                    useCase(mockk<WorktreeOpsGateway>(relaxUnitFun = true), refs, stack)
                        .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.TAG_MOVE)
            }
        }

        `when`("reset 이 성공한 순간 호출자가 취소되면") {
            val stack = UndoStack()
            val worktreeOps = mockk<WorktreeOpsGateway>(relaxUnitFun = true)

            then("이동 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { worktreeOps.hardResetBranch(any(), any(), any()) } coAnswers {
                        callerJob.cancel()
                    }
                    useCase(worktreeOps, suspendingRefGateway(), stack)
                        .execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.BRANCH_MOVE)
            }
        }

        `when`("expected 를 읽는 중에 취소되면") {
            val stack = UndoStack()
            val untouched = mockk<WorktreeOpsGateway>(relaxUnitFun = true)

            then("아직 변경 전이므로 reset 을 실행하지 않는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    val refs = mockk<RefGateway>(relaxUnitFun = true)
                    coEvery { refs.listBranches() } coAnswers {
                        callerJob.cancel()
                        listOf(branch(MAIN, MAIN_HEAD, isCurrent = true), branch(FEATURE, FEATURE_HEAD))
                    }
                    useCase(untouched, refs, stack).execute(GraphOperation.ResetBranch(MAIN, NEW_HEAD))
                }

                cancelled shouldBe true
                coVerify(exactly = 0) { untouched.hardResetBranch(any(), any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }

        `when`("태그 목록을 읽는 중에 취소되면") {
            val stack = UndoStack()
            val untouched = mockk<RefGateway>(relaxUnitFun = true)

            then("아직 변경 전이므로 태그를 옮기지 않고 기록도 남기지 않는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { untouched.listTags() } coAnswers {
                        callerJob.cancel()
                        listOf(tag(OLD_COMMIT))
                    }
                    useCase(succeedingOps(), untouched, stack)
                        .execute(GraphOperation.MoveTag(RELEASE_TAG, NEW_HEAD))
                }

                cancelled shouldBe true
                coVerify(exactly = 0) { untouched.moveTag(any(), any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }

        `when`("호출자가 이미 취소된 뒤에 실행하면") {
            val stack = UndoStack()
            val untouched = mockk<WorktreeOpsGateway>(relaxUnitFun = true)

            then("아직 아무것도 바뀌지 않았으므로 조작을 시작하지 않는다") {
                afterCallerCancelled {
                    useCase(untouched, refGateway(), stack)
                        .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))
                }

                coVerify(exactly = 0) { untouched.runOnBranch(any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("Git 변경 뒤 Undo 기록이 실패한 조작") {
        `when`("기록이 UndineException 으로 실패하면") {
            val stack = UndoStack()
            val recordFailure = UndineException.StateViolation("baseline unreadable")
            val refs = refGatewayFailingOnRead(recordFailure)
            val outcome = useCase(succeedingOps(), refs, stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("변경 실패로 승격하지 않고 기록 실패 사유를 결과에 담아 올린다") {
                outcome.shouldBeInstanceOf<GraphOperationOutcome.Completed>()
                    .undoRecordFailure shouldBe recordFailure
                stack.history().shouldBeEmpty()
            }
        }

        `when`("기록이 취소로 끝나면") {
            val stack = UndoStack()
            val refs = refGatewayFailingOnRead(CancellationException("recording cancelled"))

            then("취소를 삼키지 않고 그대로 올린다") {
                shouldThrow<CancellationException> {
                    useCase(succeedingOps(), refs, stack)
                        .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))
                }
                stack.history().shouldBeEmpty()
            }
        }
    }
})
