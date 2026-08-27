package dev.undine.application.graphops

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.BranchOperation
import dev.undine.domain.BranchOperationResult
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
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

/** 변경 Gateway 가 자기 임계 구역에서 캡처해 결과로 준 **변경 직후** 기준 상태 (UND-73). */
private val BASELINE_AFTER = RepositoryBaseline(branch = MAIN, head = NEW_HEAD)

/** 변경 뒤 앱 내부의 다른 조작이 브랜치를 옮긴 자리. 기록이 사후 조회를 하면 이 값이 남는다. */
private val INTERLEAVED_HEAD = CommitId.of("e".repeat(40))

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
    coEvery { it.moveBranch(any(), any(), any()) } returns BASELINE_AFTER
    coEvery { it.moveTag(any(), any(), any()) } returns BASELINE_AFTER
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
    coEvery { it.moveTag(any(), any(), any()) } returns BASELINE_AFTER
}

private fun succeedingOps(
    result: BranchOperationResult = BranchOperationResult.Succeeded(MAIN, MAIN_HEAD, NEW_HEAD, BASELINE_AFTER),
): WorktreeOpsGateway = resettingOps().also {
    coEvery { it.runOnBranch(any(), any()) } returns result
}

/** reset 이 성공하고 **변경 직후 기준 상태**를 결과로 주는 대역. */
private fun resettingOps(): WorktreeOpsGateway = mockk<WorktreeOpsGateway>(relaxUnitFun = true).also {
    coEvery { it.hardResetBranch(any(), any(), any()) } returns BASELINE_AFTER
}

private fun useCase(
    worktreeOps: WorktreeOpsGateway,
    refs: RefGateway,
    stack: UndoStack,
    recorder: OperationRecorder = OperationRecorder(refs, stack),
): ExecuteGraphOperationUseCase = ExecuteGraphOperationUseCase(worktreeOps, refs, recorder)

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
 * Undo 기록만 [failure] 로 실패하게 만든다 — Git 변경은 이미 적용된 뒤이므로 이 경계가 곧
 * "저장소는 바뀌었는데 되돌릴 항목만 없는" 경우다.
 *
 * 기준 상태를 조회하지 못하게 만드는 방식은 더 이상 쓸 수 없다. 기록은 변경 결과가 준 값을 그대로
 * 쓰고 `RefGateway` 를 읽지 않기 때문이다 (UND-73) — 기록 자체를 실패시켜야 이 경로에 닿는다.
 */
private fun recorderFailingWith(failure: Throwable): OperationRecorder = mockk<OperationRecorder>().also {
    coEvery { it.record(any(), any(), any(), any()) } throws failure
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
            val worktreeOps =
                succeedingOps(BranchOperationResult.Succeeded(FEATURE, FEATURE_HEAD, NEW_HEAD, BASELINE_AFTER))
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
            val worktreeOps = resettingOps()
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
            val worktreeOps = resettingOps()
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
            val worktreeOps = resettingOps()

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
            val worktreeOps = resettingOps()
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
                    useCase(resettingOps(), refs, stack)
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
                    useCase(resettingOps(), refs, stack)
                        .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))
                }
                coVerify(exactly = 0) { refs.moveTag(any(), any(), any()) }
                stack.history().shouldBeEmpty()
            }
        }
    }

    given("변경과 기록 사이에 앱 안의 다른 조작이 끼어든 경우") {
        // 기록 시점의 조회는 이미 끼어든 조작까지 반영돼 있다 — 사후 조회로 기록하면 그 값이 남는다.
        val interleaved = listOf(branch(MAIN, INTERLEAVED_HEAD, isCurrent = true))

        `when`("병합을 실행하면") {
            val stack = UndoStack()
            useCase(succeedingOps(), refGateway(branches = interleaved), stack)
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("결과가 준 기준 상태를 기록한다 — 끼어든 변경이 섞이지 않는다") {
                stack.peek()?.baseline shouldBe BASELINE_AFTER
            }
        }

        `when`("reset 을 실행하면") {
            val stack = UndoStack()
            val refs = refGateway(branches = listOf(branch(MAIN, MAIN_HEAD, isCurrent = true)))
            // 조작 직전 조회(expected)는 정상 값을, 기록 시점 조회는 끼어든 값을 보게 만든다.
            coEvery { refs.listBranches() } returnsMany listOf(listOf(branch(MAIN, MAIN_HEAD, true)), interleaved)
            useCase(resettingOps(), refs, stack).execute(GraphOperation.ResetBranch(branch = MAIN, to = OLD_COMMIT))

            then("hardResetBranch 결과가 준 기준 상태를 기록한다") {
                stack.peek()?.baseline shouldBe BASELINE_AFTER
            }
        }

        `when`("태그를 옮기면") {
            val stack = UndoStack()
            useCase(resettingOps(), refGateway(branches = interleaved), stack)
                .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))

            then("moveTag 결과가 준 기준 상태를 기록한다") {
                stack.peek()?.baseline shouldBe BASELINE_AFTER
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
                        BranchOperationResult.Succeeded(MAIN, MAIN_HEAD, NEW_HEAD, BASELINE_AFTER)
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
            coEvery { refs.moveTag(any(), any(), any()) } coAnswers { BASELINE_AFTER }

            then("이동 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { refs.moveTag(any(), any(), any()) } coAnswers {
                        callerJob.cancel()
                        BASELINE_AFTER
                    }
                    useCase(resettingOps(), refs, stack)
                        .execute(GraphOperation.MoveTag(tag = RELEASE_TAG, to = NEW_HEAD))
                }

                cancelled shouldBe true
                stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.TAG_MOVE)
            }
        }

        `when`("reset 이 성공한 순간 호출자가 취소되면") {
            val stack = UndoStack()
            val worktreeOps = resettingOps()

            then("이동 기록이 정확히 1건 남는다") {
                val cancelled = cancellingCallerOnChange { callerJob ->
                    coEvery { worktreeOps.hardResetBranch(any(), any(), any()) } coAnswers {
                        callerJob.cancel()
                        BASELINE_AFTER
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
            val untouched = resettingOps()

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
            val untouched = resettingOps()

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
            val recordFailure = UndineException.StateViolation("undo stack unavailable")
            val outcome = useCase(succeedingOps(), refGateway(), stack, recorderFailingWith(recordFailure))
                .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))

            then("변경 실패로 승격하지 않고 기록 실패 사유를 결과에 담아 올린다") {
                outcome.shouldBeInstanceOf<GraphOperationOutcome.Completed>()
                    .undoRecordFailure shouldBe recordFailure
                stack.history().shouldBeEmpty()
            }
        }

        `when`("기록이 취소로 끝나면") {
            val stack = UndoStack()
            val recorder = recorderFailingWith(CancellationException("recording cancelled"))

            then("취소를 삼키지 않고 그대로 올린다") {
                shouldThrow<CancellationException> {
                    useCase(succeedingOps(), refGateway(), stack, recorder)
                        .execute(GraphOperation.Merge(source = FEATURE, into = BranchTarget.Named(MAIN)))
                }
                stack.history().shouldBeEmpty()
            }
        }
    }
})
