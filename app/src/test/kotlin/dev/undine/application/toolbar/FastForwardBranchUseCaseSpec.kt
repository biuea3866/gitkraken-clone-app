package dev.undine.application.toolbar

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.testsupport.PassThroughSessionBinding
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.recorderOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

private const val REMOTE = "origin"
private val LOCAL = RefName("feature")
private val UPSTREAM = RefName("origin/feature")
private val LOCAL_TARGET = CommitId.of("1".repeat(40))
private val REMOTE_TARGET = CommitId.of("2".repeat(40))

private fun localBranch(
    isCurrent: Boolean = false,
    upstream: RefName? = UPSTREAM,
    target: CommitId = LOCAL_TARGET,
): Branch = Branch(
    name = LOCAL,
    target = target,
    isCurrent = isCurrent,
    isRemote = false,
    upstream = upstream,
    ahead = 0,
    behind = 0,
)

private fun trackingBranch(target: CommitId = REMOTE_TARGET): Branch = Branch(
    name = UPSTREAM,
    target = target,
    isCurrent = false,
    isRemote = true,
    upstream = null,
    ahead = 0,
    behind = 0,
)

/**
 * 지목 pull — fetch → 자손 판정 → 조건부 이동의 **순서와 거부 경로**를 본다.
 *
 * 이 UseCase 의 요점은 "빨리 감기가 아니면 아무것도 하지 않는다" 이므로, 거부 경로에서
 * `moveBranch` 와 `RemoteGateway.pull` 이 **호출되지 않는지**까지 확인한다 (결정 D3).
 */
class FastForwardBranchUseCaseSpec : BehaviorSpec({

    fun useCaseOn(
        refGateway: RefGateway,
        remoteGateway: RemoteGateway,
        stack: UndoStack = UndoStack(),
    ): FastForwardBranchUseCase = FastForwardBranchUseCase(
        fetchRemote = FetchRemoteUseCase(remoteGateway),
        refGateway = refGateway,
        operationRecorder = recorderOf(stack),
        sessionBinding = PassThroughSessionBinding,
    )

    given("추적 원격이 로컬보다 앞선 브랜치") {

        `when`("지목해 받으면") {
            then("fetch 뒤 자손 판정을 거쳐 expected 를 건 조건부 이동으로 옮긴다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns listOf(localBranch(), trackingBranch())
                coEvery { refGateway.isDescendantOf(REMOTE_TARGET, LOCAL_TARGET) } returns true
                coEvery { refGateway.moveBranch(LOCAL, REMOTE_TARGET, LOCAL_TARGET) } returns
                    baselineOf(LOCAL_TARGET)

                val outcome = useCaseOn(refGateway, remoteGateway).execute(localBranch(), REMOTE) { }

                outcome.shouldBeInstanceOf<FastForwardOutcome.FastForwarded>().to shouldBe REMOTE_TARGET
                coVerify(ordering = io.mockk.Ordering.ORDERED) {
                    remoteGateway.fetch(REMOTE, any())
                    refGateway.isDescendantOf(REMOTE_TARGET, LOCAL_TARGET)
                    refGateway.moveBranch(LOCAL, REMOTE_TARGET, LOCAL_TARGET)
                }
            }

            then("이동을 되돌릴 수 있는 항목으로 실행 이력에 남긴다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                val stack = UndoStack()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns listOf(localBranch(), trackingBranch())
                coEvery { refGateway.isDescendantOf(REMOTE_TARGET, LOCAL_TARGET) } returns true
                coEvery { refGateway.moveBranch(LOCAL, REMOTE_TARGET, LOCAL_TARGET) } returns
                    baselineOf(LOCAL_TARGET)

                useCaseOn(refGateway, remoteGateway, stack).execute(localBranch(), REMOTE) { }

                val entry = stack.history().single()
                entry.operation shouldBe GitOperationKind.BRANCH_FAST_FORWARD
                entry.strategy shouldBe UndoStrategy.MoveBranchTo(
                    branch = LOCAL,
                    previous = LOCAL_TARGET,
                    expected = REMOTE_TARGET,
                )
            }
        }
    }

    given("갈라진 추적 원격") {

        `when`("지목해 받으면") {
            then("빨리 감기가 아니므로 옮기지도 병합하지도 않고 사유를 돌려준다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns listOf(localBranch(), trackingBranch())
                coEvery { refGateway.isDescendantOf(REMOTE_TARGET, LOCAL_TARGET) } returns false

                val outcome = useCaseOn(refGateway, remoteGateway).execute(localBranch(), REMOTE) { }

                outcome shouldBe FastForwardOutcome.Refused(LOCAL, FastForwardRefusal.NOT_FAST_FORWARD)
                coVerify(exactly = 0) { refGateway.moveBranch(any(), any(), any()) }
                coVerify(exactly = 0) { remoteGateway.pull(any(), any()) }
            }
        }
    }

    given("이미 원격과 같은 위치의 브랜치") {

        `when`("지목해 받으면") {
            then("옮기지 않고 이미 최신임을 알린다 — 빈 이동을 이력에 남기지 않는다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                val stack = UndoStack()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns
                    listOf(localBranch(), trackingBranch(target = LOCAL_TARGET))

                val outcome = useCaseOn(refGateway, remoteGateway, stack).execute(localBranch(), REMOTE) { }

                outcome shouldBe FastForwardOutcome.AlreadyUpToDate(LOCAL)
                stack.size shouldBe 0
                coVerify(exactly = 0) { refGateway.moveBranch(any(), any(), any()) }
            }
        }
    }

    given("현재 체크아웃된 브랜치") {

        `when`("지목해 받으면") {
            then("fetch 조차 하지 않고 거부한다 — 포인터만 옮기면 워킹트리가 어긋난다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()

                val outcome = useCaseOn(refGateway, remoteGateway)
                    .execute(localBranch(isCurrent = true), REMOTE) { }

                outcome shouldBe FastForwardOutcome.Refused(LOCAL, FastForwardRefusal.CURRENT_BRANCH)
                coVerify(exactly = 0) { remoteGateway.fetch(any(), any()) }
            }
        }
    }

    given("추적 브랜치가 없는 브랜치") {

        `when`("지목해 받으면") {
            then("무엇을 받을지 정할 수 없어 거부한다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()

                val outcome = useCaseOn(refGateway, remoteGateway)
                    .execute(localBranch(upstream = null), REMOTE) { }

                outcome shouldBe FastForwardOutcome.Refused(LOCAL, FastForwardRefusal.NO_UPSTREAM)
                coVerify(exactly = 0) { remoteGateway.fetch(any(), any()) }
            }
        }
    }

    given("fetch 로도 추적 원격 참조가 생기지 않은 브랜치") {

        `when`("지목해 받으면") {
            then("옮길 대상이 없어 거부한다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns listOf(localBranch())

                val outcome = useCaseOn(refGateway, remoteGateway).execute(localBranch(), REMOTE) { }

                outcome shouldBe FastForwardOutcome.Refused(LOCAL, FastForwardRefusal.NO_UPSTREAM)
                coVerify(exactly = 0) { refGateway.moveBranch(any(), any(), any()) }
            }
        }
    }

    given("fetch 와 갱신 사이에 사라진 로컬 브랜치") {

        `when`("지목해 받으면") {
            then("없다고 알린다 — 없는 브랜치를 만들지 않는다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                coEvery { refGateway.listBranches() } returns listOf(trackingBranch())

                shouldThrow<UndineException.NotFound> {
                    useCaseOn(refGateway, remoteGateway).execute(localBranch(), REMOTE) { }
                }
            }
        }
    }

    given("fetch 뒤 로컬 브랜치가 다른 곳으로 움직인 경우") {

        `when`("조건부 이동이 거부되면") {
            then("그 거부가 성공으로 접히지 않고 그대로 올라온다") {
                val refGateway = mockk<RefGateway>()
                val remoteGateway = mockk<RemoteGateway>()
                val movedTarget = CommitId.of("3".repeat(40))
                coEvery { remoteGateway.fetch(REMOTE, any()) } returns emptyList()
                // 화면 스냅샷은 옛 위치지만 조회는 새 위치를 준다 — 기대 위치는 **조회한 값**이어야 한다.
                coEvery { refGateway.listBranches() } returns
                    listOf(localBranch(target = movedTarget), trackingBranch())
                coEvery { refGateway.isDescendantOf(REMOTE_TARGET, movedTarget) } returns true
                coEvery { refGateway.moveBranch(LOCAL, REMOTE_TARGET, movedTarget) } throws
                    UndineException.StateViolation("기대한 위치와 달라 참조를 옮기지 않았습니다")

                shouldThrow<UndineException.StateViolation> {
                    useCaseOn(refGateway, remoteGateway).execute(localBranch(), REMOTE) { }
                }
            }
        }
    }
})
