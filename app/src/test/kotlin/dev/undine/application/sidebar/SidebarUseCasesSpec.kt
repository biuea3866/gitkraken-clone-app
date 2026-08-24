package dev.undine.application.sidebar

import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag
import dev.undine.domain.UndineException
import dev.undine.domain.WorktreeOpsGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

private fun commitId(prefix: String): CommitId = CommitId.of(prefix.padEnd(40, '0'))

private fun branch(name: String, remote: Boolean = false): Branch = Branch(
    name = RefName(name),
    target = commitId("a1"),
    isCurrent = false,
    isRemote = remote,
    upstream = null,
    ahead = 0,
    behind = 0,
)

private val TAG_V1 = Tag(
    name = RefName("v1.0.0"),
    target = commitId("b2"),
    isAnnotated = false,
    message = null,
    tagger = null,
)

private val STASH_ENTRY = StashEntry(
    index = 0,
    message = "작업 중",
    target = commitId("c3"),
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    includedUntracked = false,
)

/** 사이드바 UseCase — Gateway 호출과 결과 조합만 하고 실패를 삼키지 않는지 본다. */
class SidebarUseCasesSpec : BehaviorSpec({

    given("브랜치·태그·스태시를 모두 돌려주는 Gateway") {
        val refGateway = mockk<RefGateway>()
        val worktreeOpsGateway = mockk<WorktreeOpsGateway>()
        coEvery { refGateway.listBranches() } returns listOf(branch("main"), branch("origin/main", remote = true))
        coEvery { refGateway.listTags() } returns listOf(TAG_V1)
        coEvery { worktreeOpsGateway.stashList() } returns listOf(STASH_ENTRY)

        `when`("사이드바 참조를 한 번 불러오면") {
            val refs = LoadSidebarRefsUseCase(refGateway, worktreeOpsGateway).invoke()

            then("세 목록이 하나로 묶여 돌아온다") {
                refs.branches.map { it.name.value } shouldBe listOf("main", "origin/main")
                refs.tags shouldBe listOf(TAG_V1)
                refs.stashes shouldBe listOf(STASH_ENTRY)
            }

            then("각 목록 조회는 정확히 한 번씩만 일어난다") {
                coVerify(exactly = 1) { refGateway.listBranches() }
                coVerify(exactly = 1) { refGateway.listTags() }
                coVerify(exactly = 1) { worktreeOpsGateway.stashList() }
            }
        }
    }

    given("브랜치 조회가 실패하는 Gateway") {
        val refGateway = mockk<RefGateway>()
        val worktreeOpsGateway = mockk<WorktreeOpsGateway>()
        coEvery { refGateway.listBranches() } throws UndineException.GitOperationFailed("ref.listBranches")

        `when`("사이드바 참조를 불러오면") {
            then("빈 목록으로 바꾸지 않고 실패가 그대로 올라온다") {
                shouldThrow<UndineException.GitOperationFailed> {
                    LoadSidebarRefsUseCase(refGateway, worktreeOpsGateway).invoke()
                }
            }
        }
    }

    given("브랜치를 조작하는 Gateway") {
        val refGateway = mockk<RefGateway>(relaxUnitFun = true)

        `when`("체크아웃 UseCase 를 실행하면") {
            CheckoutBranchUseCase(refGateway).invoke(RefName("feature/login"))

            then("강제 없이 체크아웃을 위임한다") {
                coVerify(exactly = 1) { refGateway.checkout(RefName("feature/login"), force = false) }
            }
        }

        `when`("이름 변경 UseCase 를 실행하면") {
            RenameBranchUseCase(refGateway).invoke(RefName("old"), RefName("new"))

            then("이전 이름과 새 이름을 그대로 위임한다") {
                coVerify(exactly = 1) { refGateway.renameBranch(RefName("old"), RefName("new")) }
            }
        }
    }

    given("미병합 브랜치를 거부하는 Gateway") {
        val refGateway = mockk<RefGateway>()
        coEvery { refGateway.deleteBranch(RefName("feature/wip"), force = false) } returns
            DeleteBranchResult.REFUSED_UNMERGED
        coEvery { refGateway.deleteBranch(RefName("feature/wip"), force = true) } returns
            DeleteBranchResult.DELETED

        `when`("비강제 삭제를 요청하면") {
            val result = DeleteBranchUseCase(refGateway).invoke(RefName("feature/wip"), force = false)

            then("거부 결과가 그대로 전달된다") {
                result shouldBe DeleteBranchResult.REFUSED_UNMERGED
            }
        }

        `when`("강제 삭제를 요청하면") {
            val result = DeleteBranchUseCase(refGateway).invoke(RefName("feature/wip"), force = true)

            then("삭제 결과가 그대로 전달된다") {
                result shouldBe DeleteBranchResult.DELETED
            }
        }
    }
})
