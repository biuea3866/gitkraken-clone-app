package dev.undine.domain.cherrypick

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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * cherry-pick 규칙 — 시작 전 워킹트리 검사, **선택 순서가 아닌 이력 순서** 적용, 충돌에서 멈춤,
 * 확인 없는 중단 차단.
 *
 * Gateway 는 대역이다. 여기서 보는 것은 "규칙을 지키는가" 이고, JGit 이 실제로 그렇게 동작하는가는
 * `CherryPickGatewayImplSpec` 이 실제 저장소로 본다.
 */
class CherryPickServiceSpec : FunSpec({

    test("선택 순서가 아니라 이력 순서로 적용한다") {
        // 사용자가 최신을 먼저 클릭했지만 적용은 오래된 것부터여야 한다.
        val gateway = FakeCherryPickGateway(historyOrder = listOf(commitId(1), commitId(2), commitId(3)))
        val service = serviceWith(gateway)

        val result = service.cherryPick(listOf(commitId(3), commitId(1), commitId(2)), recordOrigin = false)

        gateway.appliedOrder shouldContainExactly listOf(commitId(1), commitId(2), commitId(3))
        result shouldBe CherryPickResult.Applied(
            created = gateway.appliedOrder.map { created(it) },
            previousHead = commitId(9),
            baseline = baselineOf(created(gateway.appliedOrder.last())),
        )
    }

    test("워킹트리가 더티하면 시작하지 않는다") {
        val gateway = FakeCherryPickGateway(historyOrder = listOf(commitId(1)))
        val service = serviceWith(gateway, status = dirtyStatus())

        shouldThrow<UndineException.DirtyWorkingTree> {
            service.cherryPick(listOf(commitId(1)), recordOrigin = false)
        }

        // 시작 전 검사에서 막혔으므로 Gateway 로 가지 않는다.
        gateway.appliedOrder.shouldBeEmpty()
    }

    test("적용할 커밋이 없으면 상태 위반이다") {
        val service = serviceWith(FakeCherryPickGateway())

        shouldThrow<UndineException.StateViolation> { service.cherryPick(emptyList(), recordOrigin = false) }
    }

    test("충돌하면 그 자리에서 멈추고 어디까지 갔는지 알려준다") {
        val gateway = FakeCherryPickGateway(
            historyOrder = listOf(commitId(1), commitId(2), commitId(3)),
            conflictAt = commitId(2),
        )
        val service = serviceWith(gateway)

        val result = service.cherryPick(listOf(commitId(1), commitId(2), commitId(3)), recordOrigin = false)

        result shouldBe CherryPickResult.Conflicted(
            paths = listOf("shared.txt"),
            stoppedAt = commitId(2),
            created = listOf(created(commitId(1))),
            // 멈추기 전에 만든 커밋은 중단해도 남는다 — 그 묶음의 되돌리기 재료가 함께 실린다.
            previousHead = commitId(9),
            baseline = baselineOf(created(commitId(1))),
        )
        // 남은 커밋을 계속 적용하지 않는다 — 충돌이 여러 겹으로 쌓이면 해결할 수 없다.
        gateway.appliedOrder shouldContainExactly listOf(commitId(1), commitId(2))
    }

    test("적용할 변경이 없으면 이미 적용됨으로 구분한다") {
        val gateway = FakeCherryPickGateway(historyOrder = listOf(commitId(1)), emptyAt = commitId(1))
        val service = serviceWith(gateway)

        service.cherryPick(listOf(commitId(1)), recordOrigin = false) shouldBe CherryPickResult.AlreadyApplied
    }

    test("원본 기록 옵션이 Gateway 까지 전달된다") {
        val gateway = FakeCherryPickGateway(historyOrder = listOf(commitId(1)))
        val service = serviceWith(gateway)

        service.cherryPick(listOf(commitId(1)), recordOrigin = true)

        gateway.recordOriginRequests shouldContainExactly listOf(true)
    }

    test("진행 중이 아니면 계속·중단이 상태 위반이다") {
        val gateway = FakeCherryPickGateway(state = RepositoryState.NORMAL)
        val service = serviceWith(gateway)

        shouldThrow<UndineException.StateViolation> { service.continueAfterResolve() }
        shouldThrow<UndineException.StateViolation> {
            service.abort(CherryPickAbortConfirmation.ofDiscardedPaths(emptyList()))
        }
    }

    test("확인 뒤에 생긴 편집이 있으면 중단하지 않는다") {
        val gateway = FakeCherryPickGateway(state = RepositoryState.CHERRY_PICKING)
        val service = serviceWith(gateway, status = dirtyStatus())

        // 사용자가 본 목록에 없는 편집이 남아 있다 — 모르고 확인한 것이므로 되돌리지 않는다.
        shouldThrow<UndineException.StateViolation> {
            service.abort(CherryPickAbortConfirmation.ofDiscardedPaths(emptyList()))
        }
        gateway.aborted shouldBe false
    }

    test("확인 목록이 지금 사라질 편집을 담고 있으면 중단한다") {
        val gateway = FakeCherryPickGateway(state = RepositoryState.CHERRY_PICKING)
        val service = serviceWith(gateway, status = dirtyStatus())

        service.abort(CherryPickAbortConfirmation.ofDiscardedPaths(listOf("edited.txt")))

        gateway.aborted shouldBe true
    }

    test("이어가서 충돌하면 여전히 그 커밋에서 멈춰 있다") {
        val gateway = FakeCherryPickGateway(
            state = RepositoryState.CHERRY_PICKING,
            continueStep = CherryPickStep.Conflicted(listOf("shared.txt")),
            stoppedAt = commitId(2),
        )
        val service = serviceWith(gateway)

        service.continueAfterResolve() shouldBe CherryPickResult.Conflicted(
            paths = listOf("shared.txt"),
            stoppedAt = commitId(2),
            created = emptyList(),
            // 만든 커밋이 없으면 되돌릴 것도 없다.
            previousHead = null,
            baseline = null,
        )
    }
})

private fun created(origin: CommitId): CommitId = commitId(origin.value.trimStart('0').toInt(16) + PICK_OFFSET)

/** 적용 결과 커밋은 원본과 다른 해시다 — 대역도 그 사실을 흉내내야 검증이 의미를 갖는다. */
private const val PICK_OFFSET = 100

private fun serviceWith(
    gateway: FakeCherryPickGateway,
    status: WorkingTreeStatus = cleanStatus(),
): CherryPickService = CherryPickService(FixedStatusRepositoryGateway(status), gateway)

private fun cleanStatus() = WorkingTreeStatus(emptyList(), emptyList(), emptyList(), emptyList())

private fun dirtyStatus() = WorkingTreeStatus(
    staged = emptyList(),
    unstaged = listOf(FileChange("edited.txt", null, ChangeType.MODIFIED, 1, 0, isBinary = false)),
    untracked = emptyList(),
    conflicted = emptyList(),
)

/** 적용 호출을 기록하는 대역. 순서·옵션·중단 여부가 검증 대상이다. */
private class FakeCherryPickGateway(
    private val historyOrder: List<CommitId> = emptyList(),
    private val conflictAt: CommitId? = null,
    private val emptyAt: CommitId? = null,
    private val state: RepositoryState = RepositoryState.NORMAL,
    private val continueStep: CherryPickStep = createdStep(commitId(1)),
    private val stoppedAt: CommitId? = null,
) : CherryPickGateway {

    val appliedOrder = mutableListOf<CommitId>()
    val recordOriginRequests = mutableListOf<Boolean>()
    var aborted: Boolean = false
        private set

    override suspend fun repositoryState(): RepositoryState = state

    override suspend fun orderOldestFirst(commits: List<CommitId>): List<CommitId> =
        historyOrder.filter { it in commits }

    override suspend fun apply(commit: CommitId, recordOrigin: Boolean): CherryPickStep {
        appliedOrder += commit
        recordOriginRequests += recordOrigin
        return when (commit) {
            conflictAt -> CherryPickStep.Conflicted(listOf("shared.txt"))
            emptyAt -> CherryPickStep.Empty
            else -> createdStep(created(commit))
        }
    }

    override suspend fun stoppedAt(): CommitId? = stoppedAt

    override suspend fun continueAfterResolve(): CherryPickStep = continueStep

    override suspend fun abort(confirmation: CherryPickAbortConfirmation) {
        aborted = true
    }
}

/** 고정된 워킹트리 상태만 답하는 대역. */
private class FixedStatusRepositoryGateway(private val status: WorkingTreeStatus) : RepositoryGateway {

    override suspend fun open(path: RepositoryPath): OpenedRepository =
        OpenedRepository(state = RepositoryState.NORMAL, currentBranch = RefName("refs/heads/main"))

    override suspend fun status(): WorkingTreeStatus = status

    override suspend fun close() = Unit
}

/** 적용 단계가 자기 임계 구역에서 캡처해 돌려주는 되돌리기 재료 (UND-73). */
private fun createdStep(commit: CommitId): CherryPickStep.Created =
    CherryPickStep.Created(commit, previousHead = commitId(9), baseline = baselineOf(commit))
