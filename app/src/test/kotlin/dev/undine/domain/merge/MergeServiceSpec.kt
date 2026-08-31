package dev.undine.domain.merge

import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.FileChange
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val PREVIOUS = commitId(1)

private const val HEAD_HASH = "1111111111111111111111111111111111111111"
private const val SKIPPED_HASH = "2222222222222222222222222222222222222222"
private const val OTHER_HASH = "3333333333333333333333333333333333333333"
private const val TARGET_BRANCH = "refs/heads/feature"
private const val CONFLICTED_FILE = "conflict.txt"
private const val EDITED_FILE = "edited.txt"

/** 화면이 사라질 편집 목록을 보여 주고 받은 확인 — [DIRTY_STATUS] 의 추적 중 편집을 모두 담는다. */
private val CONFIRMED_ABORT = AbortConfirmation.ofDiscardedPaths(listOf(CONFLICTED_FILE, EDITED_FILE))

/** 화면이 사라질 커밋을 보여 주고 받은 확인 — fake 가 멈춰 있다고 답하는 커밋과 같다. */
private val CONFIRMED_SKIP = SkipConfirmation.ofSkippedCommit(CommitId.of(SKIPPED_HASH))

private val CLEAN_STATUS = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = emptyList(),
    untracked = emptyList(),
    conflicted = emptyList(),
)

private val DIRTY_STATUS = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = listOf(
        FileChange(
            path = EDITED_FILE,
            previousPath = null,
            changeType = ChangeType.MODIFIED,
            addedLines = 0,
            deletedLines = 0,
            isBinary = false,
        ),
    ),
    untracked = listOf("new.txt"),
    conflicted = emptyList(),
)

/** 워킹트리 상태만 돌려주는 최소 fake. 열기·닫기는 이 서비스가 쓰지 않는다. */
private class FakeRepositoryGateway(private val status: WorkingTreeStatus) : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository =
        error("MergeService 는 저장소를 열지 않는다")

    override suspend fun status(): WorkingTreeStatus = status

    override suspend fun close() = error("MergeService 는 저장소를 닫지 않는다")
}

/** 어떤 메서드가 호출됐는지 기록하는 fake — "시작조차 하지 않았다" 를 검증하려면 호출 여부가 필요하다. */
private class RecordingMergeGateway(
    private val state: RepositoryState = RepositoryState.NORMAL,
    private val mergeResult: MergeResult = MergeResult.AlreadyUpToDate,
    private val rebaseResult: RebaseResult = RebaseResult.AlreadyUpToDate,
    private val rebasingCommit: CommitId? = CommitId.of(SKIPPED_HASH),
) : MergeGateway {

    val calls = mutableListOf<String>()
    var mergeTarget: RefName? = null
    var mergeAllowedFastForward: Boolean? = null
    var rebaseTarget: RefName? = null

    override suspend fun repositoryState(): RepositoryState = state

    override suspend fun merge(target: RefName, allowFastForward: Boolean): MergeResult {
        calls += "merge"
        mergeTarget = target
        mergeAllowedFastForward = allowFastForward
        return mergeResult
    }

    override suspend fun continueMerge(): MergeResult {
        calls += "continueMerge"
        return mergeResult
    }

    override suspend fun abortMerge(confirmation: AbortConfirmation) {
        calls += "abortMerge"
    }

    override suspend fun rebase(target: RefName): RebaseResult {
        calls += "rebase"
        rebaseTarget = target
        return rebaseResult
    }

    override suspend fun continueRebase(): RebaseResult {
        calls += "continueRebase"
        return rebaseResult
    }

    override suspend fun rebasingCommit(): CommitId? = rebasingCommit

    override suspend fun skipRebaseCommit(confirmation: SkipConfirmation): RebaseResult {
        calls += "skipRebaseCommit"
        return rebaseResult
    }

    override suspend fun abortRebase(confirmation: AbortConfirmation) {
        calls += "abortRebase"
    }
}

private fun serviceOf(
    status: WorkingTreeStatus = CLEAN_STATUS,
    gateway: RecordingMergeGateway = RecordingMergeGateway(),
): MergeService = MergeService(FakeRepositoryGateway(status), gateway)

class MergeServiceSpec : BehaviorSpec({

    given("워킹트리가 깨끗한 저장소") {

        `when`("병합을 시작하면") {
            val gateway = RecordingMergeGateway(
                mergeResult = merged(),
            )
            val result = serviceOf(gateway = gateway).merge(RefName(TARGET_BRANCH), allowFastForward = false)

            then("대상과 fast-forward 옵션이 그대로 Gateway 에 전달되고 결과가 올라온다") {
                gateway.calls shouldContainExactly listOf("merge")
                gateway.mergeTarget shouldBe RefName(TARGET_BRANCH)
                gateway.mergeAllowedFastForward shouldBe false
                result shouldBe merged()
            }
        }

        `when`("fast-forward 옵션을 주지 않고 병합하면") {
            val gateway = RecordingMergeGateway()
            serviceOf(gateway = gateway).merge(RefName(TARGET_BRANCH))

            then("기본값은 fast-forward 허용이다") {
                gateway.mergeAllowedFastForward shouldBe true
            }
        }

        `when`("리베이스를 시작하면") {
            val gateway = RecordingMergeGateway(
                rebaseResult = rebased(),
            )
            val result = serviceOf(gateway = gateway).rebase(RefName(TARGET_BRANCH))

            then("대상이 그대로 전달되고 결과가 올라온다") {
                gateway.calls shouldContainExactly listOf("rebase")
                gateway.rebaseTarget shouldBe RefName(TARGET_BRANCH)
                result shouldBe rebased()
            }
        }

        `when`("병합이 충돌하면") {
            val gateway = RecordingMergeGateway(
                mergeResult = MergeResult.Conflicted(listOf(CONFLICTED_FILE)),
            )
            val result = serviceOf(gateway = gateway).merge(RefName(TARGET_BRANCH))

            then("예외가 아니라 Conflicted 결과가 그대로 올라온다") {
                result shouldBe MergeResult.Conflicted(listOf(CONFLICTED_FILE))
            }
        }

        `when`("이미 병합된 대상을 다시 병합하면") {
            val result = serviceOf().merge(RefName(TARGET_BRANCH))

            then("변경 없음으로 보고한다") {
                result shouldBe MergeResult.AlreadyUpToDate
            }
        }
    }

    given("커밋되지 않은 변경이 남은 워킹트리") {

        `when`("병합을 시작하면") {
            val gateway = RecordingMergeGateway()
            val thrown = shouldThrow<UndineException.DirtyWorkingTree> {
                serviceOf(status = DIRTY_STATUS, gateway = gateway).merge(RefName(TARGET_BRANCH))
            }

            then("Gateway 를 호출하지 않고 더티 경로와 함께 거부한다") {
                thrown.paths shouldContainExactly listOf(EDITED_FILE, "new.txt")
                gateway.calls.shouldBeEmpty()
            }
        }

        `when`("리베이스를 시작하면") {
            val gateway = RecordingMergeGateway()
            shouldThrow<UndineException.DirtyWorkingTree> {
                serviceOf(status = DIRTY_STATUS, gateway = gateway).rebase(RefName(TARGET_BRANCH))
            }

            then("Gateway 를 호출하지 않는다") {
                gateway.calls.shouldBeEmpty()
            }
        }
    }

    given("병합이 진행 중인 저장소") {
        val inProgress = { result: MergeResult ->
            RecordingMergeGateway(state = RepositoryState.MERGING, mergeResult = result)
        }

        `when`("충돌을 해결하고 계속하면") {
            val gateway = inProgress(merged())
            // 충돌을 해결한 워킹트리는 항상 더티하므로 계속·중단은 더티 검사를 하지 않는다.
            val result = MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway).continueMerge()

            then("더티 워킹트리와 무관하게 병합이 이어진다") {
                gateway.calls shouldContainExactly listOf("continueMerge")
                result shouldBe merged()
            }
        }

        `when`("중단하면") {
            val gateway = inProgress(MergeResult.AlreadyUpToDate)
            MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway).abort(CONFIRMED_ABORT)

            then("병합 중단 경로로 간다") {
                gateway.calls shouldContainExactly listOf("abortMerge")
            }
        }

        `when`("확인한 목록에 없는 편집이 남아 있으면") {
            val gateway = inProgress(MergeResult.AlreadyUpToDate)
            val staleConfirmation = AbortConfirmation.ofDiscardedPaths(listOf(CONFLICTED_FILE))
            val thrown = shouldThrow<UndineException.StateViolation> {
                MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway).abort(staleConfirmation)
            }

            then("사라질 편집을 사용자가 모르는 상태이므로 중단하지 않는다") {
                thrown.detail shouldBe "확인한 뒤에 생긴 편집이 있어 중단하지 않았습니다: $EDITED_FILE"
                gateway.calls.shouldBeEmpty()
            }
        }

        `when`("추적되지 않는 파일만 확인 목록에서 빠졌으면") {
            val gateway = inProgress(MergeResult.AlreadyUpToDate)
            val trackedOnly = AbortConfirmation.ofDiscardedPaths(listOf(EDITED_FILE))
            MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway).abort(trackedOnly)

            then("중단은 추적되지 않는 파일을 지우지 않으므로 그대로 진행한다") {
                gateway.calls shouldContainExactly listOf("abortMerge")
            }
        }

        `when`("확인을 만든 뒤 화면이 목록을 다시 고치면") {
            val shownPaths = mutableListOf(CONFLICTED_FILE)
            val confirmation = AbortConfirmation.ofDiscardedPaths(shownPaths)
            shownPaths += EDITED_FILE

            then("확인에 담긴 목록은 사용자가 본 그대로 남는다") {
                confirmation.discardedPaths shouldContainExactly listOf(CONFLICTED_FILE)
            }
        }

        `when`("건너뛰기를 요청하면") {
            val gateway = inProgress(MergeResult.AlreadyUpToDate)
            val thrown = shouldThrow<UndineException.StateViolation> {
                MergeService(FakeRepositoryGateway(CLEAN_STATUS), gateway).skipRebaseCommit(CONFIRMED_SKIP)
            }

            then("건너뛰기는 리베이스 전용이므로 상태 위반이다") {
                thrown.detail shouldBe "리베이스가 진행 중이 아닙니다"
                gateway.calls.shouldBeEmpty()
            }
        }
    }

    given("리베이스가 진행 중인 저장소") {
        val rebasing = RecordingMergeGateway(
            state = RepositoryState.REBASING,
            rebaseResult = rebased(),
        )

        `when`("계속·건너뛰기·중단을 호출하면") {
            val service = MergeService(FakeRepositoryGateway(DIRTY_STATUS), rebasing)
            service.continueRebase()
            service.skipRebaseCommit(CONFIRMED_SKIP)
            service.abort(CONFIRMED_ABORT)

            then("각각 리베이스 경로로 전달된다") {
                rebasing.calls shouldContainExactly listOf("continueRebase", "skipRebaseCommit", "abortRebase")
            }
        }
    }

    given("리베이스가 다른 커밋에서 멈춰 있는 저장소") {
        val gateway = RecordingMergeGateway(
            state = RepositoryState.REBASING,
            rebaseResult = rebased(),
            rebasingCommit = CommitId.of(OTHER_HASH),
        )
        val service = MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway)

        `when`("확인한 커밋과 다른 커밋을 건너뛰려 하면") {
            val thrown = shouldThrow<UndineException.StateViolation> { service.skipRebaseCommit(CONFIRMED_SKIP) }

            then("사용자가 보지 않은 커밋이 사라지지 않도록 Gateway 를 호출하지 않는다") {
                thrown.detail shouldContain "확인한 커밋과 지금 건너뛸 커밋이 달라"
                thrown.detail shouldContain OTHER_HASH
                gateway.calls.shouldBeEmpty()
            }
        }
    }

    given("리베이스 중이지만 멈춘 커밋을 읽을 수 없는 저장소") {
        val gateway = RecordingMergeGateway(
            state = RepositoryState.REBASING,
            rebaseResult = rebased(),
            rebasingCommit = null,
        )
        val service = MergeService(FakeRepositoryGateway(DIRTY_STATUS), gateway)

        `when`("건너뛰기를 요청하면") {
            val thrown = shouldThrow<UndineException.StateViolation> { service.skipRebaseCommit(CONFIRMED_SKIP) }

            then("무엇이 사라지는지 대조할 수 없으므로 Gateway 를 호출하지 않는다") {
                thrown.detail shouldBe "건너뛸 커밋을 읽을 수 없어 건너뛰지 않았습니다"
                gateway.calls.shouldBeEmpty()
            }
        }
    }

    given("진행 중인 연산이 없는 저장소") {
        val idle = RecordingMergeGateway(state = RepositoryState.NORMAL)
        val service = serviceOf(gateway = idle)

        `when`("계속을 호출하면") {
            val mergeFailure = shouldThrow<UndineException.StateViolation> { service.continueMerge() }
            val rebaseFailure = shouldThrow<UndineException.StateViolation> { service.continueRebase() }

            then("무엇이 진행 중이 아닌지 밝히며 상태 위반으로 거부한다") {
                mergeFailure.detail shouldBe "병합이 진행 중이 아닙니다"
                rebaseFailure.detail shouldBe "리베이스가 진행 중이 아닙니다"
            }
        }

        `when`("건너뛰기와 중단을 호출하면") {
            val skipFailure = shouldThrow<UndineException.StateViolation> { service.skipRebaseCommit(CONFIRMED_SKIP) }
            val abortFailure = shouldThrow<UndineException.StateViolation> { service.abort(CONFIRMED_ABORT) }

            then("Gateway 를 호출하지 않고 상태 위반으로 거부한다") {
                skipFailure.detail shouldBe "리베이스가 진행 중이 아닙니다"
                abortFailure.detail shouldBe "중단할 병합·리베이스가 진행 중이 아닙니다"
                idle.calls.shouldBeEmpty()
            }
        }
    }

    given("revert 가 진행 중인 저장소") {
        val reverting = RecordingMergeGateway(state = RepositoryState.REVERTING)

        `when`("중단을 호출하면") {
            val thrown = shouldThrow<UndineException.StateViolation> {
                serviceOf(gateway = reverting).abort(CONFIRMED_ABORT)
            }

            then("revert 중단은 이 서비스 범위가 아니므로 상태 위반이다") {
                thrown.detail shouldBe "중단할 병합·리베이스가 진행 중이 아닙니다"
                reverting.calls.shouldBeEmpty()
            }
        }
    }
})

/** 병합·리베이스 결과가 결과에 싣는 되돌리기 재료 (UND-73). 이 스펙은 그 값을 통과시키는지만 본다. */
private fun merged(): MergeResult.Succeeded =
    MergeResult.Succeeded(CommitId.of(HEAD_HASH), fastForward = false, PREVIOUS, baselineOf(CommitId.of(HEAD_HASH)))

private fun rebased(): RebaseResult.Succeeded =
    RebaseResult.Succeeded(CommitId.of(HEAD_HASH), PREVIOUS, baselineOf(CommitId.of(HEAD_HASH)))
