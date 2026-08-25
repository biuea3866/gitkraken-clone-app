package dev.undine.application.worktree

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class WorktreeUseCasesSpec : BehaviorSpec({
    given("worktree Gateway") {
        val gateway = mockk<WorktreeGateway>(relaxUnitFun = true)
        val linked = Worktree("feature", RepositoryPath("/repo-feature"), RefName("feature"), WorktreeState.LINKED)
        val listing = WorktreeListing(listOf(linked), emptyList())
        coEvery { gateway.list() } returns listing
        coEvery { gateway.add(RepositoryPath("/repo-feature"), RefName("feature")) } returns linked

        `when`("목록 조회와 추가를 요청하면") {
            LoadWorktreesUseCase(gateway).execute() shouldBe listing
            AddWorktreeUseCase(gateway).execute(RepositoryPath("/repo-feature"), RefName("feature")) shouldBe linked

            then("결과를 바꾸지 않고 Gateway에 위임한다") {
                coVerify(exactly = 1) { gateway.list() }
                coVerify(exactly = 1) { gateway.add(RepositoryPath("/repo-feature"), RefName("feature")) }
            }
        }

        `when`("더티 worktree 제거를 요청하면") {
            coEvery { gateway.remove("feature") } throws UndineException.DirtyWorkingTree(listOf("a.kt", "b.kt"))

            then("성공이나 빈 목록으로 바꾸지 않고 실패를 전달한다") {
                shouldThrow<UndineException.DirtyWorkingTree> { RemoveWorktreeUseCase(gateway).execute("feature") }
            }
        }
    }
})
