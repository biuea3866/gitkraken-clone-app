package dev.undine.application.merge

import dev.undine.domain.CommitId
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation
import dev.undine.domain.undo.UndoStack
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import dev.undine.testsupport.recorderOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val PREVIOUS = commitId(1)

private const val HEAD_HASH = "2222222222222222222222222222222222222222"
private const val TARGET_BRANCH = "refs/heads/feature"
private const val CONFLICTED_FILE = "conflict.txt"
private const val APPLICATION_SOURCE_PATH = "src/main/kotlin/dev/undine/application/merge"
private val FORBIDDEN_LAYERS = listOf("infrastructure", "presentation")

/** 화면이 사라질 편집 목록을 보여 주고 받은 확인. */
private val CONFIRMED_ABORT = AbortConfirmation.ofDiscardedPaths(listOf(CONFLICTED_FILE))

/** 화면이 사라질 커밋을 보여 주고 받은 확인 — stub 이 멈춰 있다고 답하는 커밋과 같다. */
private val CONFIRMED_SKIP = SkipConfirmation.ofSkippedCommit(CommitId.of(HEAD_HASH))

private val CLEAN_STATUS = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = emptyList(),
    untracked = emptyList(),
    conflicted = emptyList(),
)

private class FixedRepositoryGateway(private val status: WorkingTreeStatus) : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository = error("사용하지 않는다")

    override suspend fun status(): WorkingTreeStatus = status

    override suspend fun close() = error("사용하지 않는다")
}

/**
 * domain 이 내는 결과를 그대로 돌려주는 fake. UseCase 가 결과를 **바꾸거나 삼키지 않는지** 보는 것이
 * 목적이라 실행 자체는 흉내만 낸다.
 */
private class StubMergeGateway(
    private val state: RepositoryState,
    private val mergeResult: MergeResult = MergeResult.AlreadyUpToDate,
    private val rebaseResult: RebaseResult = RebaseResult.AlreadyUpToDate,
    private val failure: UndineException? = null,
) : MergeGateway {

    var aborted = false

    override suspend fun repositoryState(): RepositoryState = state

    override suspend fun rebasingCommit(): CommitId? = CommitId.of(HEAD_HASH)

    override suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult = mergeOutcome()

    override suspend fun continueMerge(): MergeResult = mergeOutcome()

    override suspend fun abortMerge(confirmation: AbortConfirmation) {
        aborted = true
    }

    override suspend fun rebase(target: RefName): RebaseResult = rebaseOutcome()

    override suspend fun continueRebase(): RebaseResult = rebaseOutcome()

    override suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult = rebaseOutcome()

    override suspend fun abortRebase(confirmation: AbortConfirmation) {
        aborted = true
    }

    private fun mergeOutcome(): MergeResult = failure?.let { throw it } ?: mergeResult

    private fun rebaseOutcome(): RebaseResult = failure?.let { throw it } ?: rebaseResult
}

private fun serviceOf(
    state: RepositoryState = RepositoryState.NORMAL,
    mergeResult: MergeResult = MergeResult.AlreadyUpToDate,
    rebaseResult: RebaseResult = RebaseResult.AlreadyUpToDate,
    failure: UndineException? = null,
    status: WorkingTreeStatus = CLEAN_STATUS,
): Pair<MergeService, StubMergeGateway> {
    val gateway = StubMergeGateway(state, mergeResult, rebaseResult, failure)
    return MergeService(FixedRepositoryGateway(status), gateway) to gateway
}

class MergeUseCaseSpec : BehaviorSpec({

    given("병합이 성공하는 도메인 서비스") {
        val succeeded = merged(fastForward = true)
        val (service, _) = serviceOf(mergeResult = succeeded)

        `when`("MergeBranchUseCase 를 실행하면") {
            val result = MergeBranchUseCase(service, recorderOf(UndoStack())).execute(RefName(TARGET_BRANCH))

            then("도메인 결과를 그대로 돌려준다") {
                result.result shouldBe succeeded
            }
        }
    }

    given("병합이 충돌하는 도메인 서비스") {
        val conflicted = MergeResult.Conflicted(listOf(CONFLICTED_FILE))
        val (service, _) = serviceOf(mergeResult = conflicted)

        `when`("MergeBranchUseCase 를 실행하면") {
            val result = MergeBranchUseCase(service, recorderOf(UndoStack())).execute(RefName(TARGET_BRANCH))

            then("충돌을 예외로 바꾸지 않고 Conflicted 를 그대로 돌려준다") {
                result.result shouldBe conflicted
            }
        }
    }

    given("리베이스가 성공하는 도메인 서비스") {
        val succeeded = rebased()

        `when`("RebaseBranchUseCase 를 실행하면") {
            val (service, _) = serviceOf(rebaseResult = succeeded)
            val result = RebaseBranchUseCase(service, recorderOf(UndoStack())).execute(RefName(TARGET_BRANCH))

            then("도메인 결과를 그대로 돌려준다") {
                result.result shouldBe succeeded
            }
        }

        `when`("리베이스 진행 중에 계속·건너뛰기를 실행하면") {
            val (service, _) = serviceOf(state = RepositoryState.REBASING, rebaseResult = succeeded)

            then("두 UseCase 모두 도메인 결과를 그대로 돌려준다") {
                ContinueRebaseUseCase(service).execute() shouldBe succeeded
                SkipRebaseCommitUseCase(service).execute(CONFIRMED_SKIP) shouldBe succeeded
            }
        }
    }

    given("병합이 진행 중인 도메인 서비스") {
        val succeeded = merged(fastForward = false)

        `when`("ContinueMergeUseCase 를 실행하면") {
            val (service, _) = serviceOf(state = RepositoryState.MERGING, mergeResult = succeeded)
            val result = ContinueMergeUseCase(service).execute()

            then("도메인 결과를 그대로 돌려준다") {
                result shouldBe succeeded
            }
        }

        `when`("AbortMergeOrRebaseUseCase 를 실행하면") {
            val (service, gateway) = serviceOf(state = RepositoryState.MERGING)
            AbortMergeOrRebaseUseCase(service).execute(CONFIRMED_ABORT)

            then("중단이 도메인까지 전달된다") {
                gateway.aborted shouldBe true
            }
        }
    }

    given("도메인 서비스가 실패를 던지는 상황") {

        `when`("더티 워킹트리로 병합을 실행하면") {
            val dirtyStatus = CLEAN_STATUS.copy(untracked = listOf("new.txt"))
            val (service, _) = serviceOf(status = dirtyStatus)

            then("DirtyWorkingTree 를 삼키지 않고 그대로 전파한다") {
                val thrown = shouldThrow<UndineException.DirtyWorkingTree> {
                    MergeBranchUseCase(service, recorderOf(UndoStack())).execute(RefName(TARGET_BRANCH))
                }
                thrown.paths shouldBe listOf("new.txt")
            }
        }

        `when`("진행 중이 아닌데 계속·중단을 실행하면") {
            val (service, _) = serviceOf(state = RepositoryState.NORMAL)

            then("StateViolation 을 삼키지 않고 그대로 전파한다") {
                shouldThrow<UndineException.StateViolation> { ContinueMergeUseCase(service).execute() }
                shouldThrow<UndineException.StateViolation> {
                    AbortMergeOrRebaseUseCase(service).execute(CONFIRMED_ABORT)
                }
            }
        }

        `when`("대상 ref 가 없어 NotFound 가 올라오면") {
            val notFound = UndineException.NotFound(UndineException.NotFound.Kind.REF, TARGET_BRANCH)
            val (service, _) = serviceOf(failure = notFound)

            then("NotFound 를 그대로 전파한다") {
                val thrown = shouldThrow<UndineException.NotFound> {
                    MergeBranchUseCase(service, recorderOf(UndoStack())).execute(RefName(TARGET_BRANCH))
                }
                thrown.kind shouldBe UndineException.NotFound.Kind.REF
                thrown.name shouldBe TARGET_BRANCH
            }
        }
    }

    given("application/merge 소스") {

        `when`("import 를 훑으면") {
            val sources = File(APPLICATION_SOURCE_PATH).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

            then("Gateway 구현체(infrastructure)나 화면(presentation)을 참조하지 않는다") {
                sources.isEmpty() shouldBe false
                sources.flatMap { source ->
                    source.readLines()
                        .withIndex()
                        .filter { (_, line) ->
                            val trimmed = line.trim()
                            trimmed.startsWith("import ") &&
                                FORBIDDEN_LAYERS.any { layer -> trimmed.contains("dev.undine.$layer") }
                        }
                        .map { (index, line) -> "${source.path}:${index + 1} ${line.trim()}" }
                }.shouldBeEmpty()
            }
        }
    }
})

/** 병합·리베이스 결과가 결과에 싣는 되돌리기 재료 (UND-73). UseCase 가 그 값을 바꾸지 않는지 본다. */
private fun merged(fastForward: Boolean): MergeResult.Succeeded =
    MergeResult.Succeeded(CommitId.of(HEAD_HASH), fastForward, PREVIOUS, baselineOf(CommitId.of(HEAD_HASH)))

private fun rebased(): RebaseResult.Succeeded =
    RebaseResult.Succeeded(CommitId.of(HEAD_HASH), PREVIOUS, baselineOf(CommitId.of(HEAD_HASH)))
