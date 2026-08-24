package dev.undine.application.diff

import dev.undine.domain.CommitId
import dev.undine.domain.DiffGateway
import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffResult
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

private const val PATH = "src/main/kotlin/dev/undine/domain/DiffLine.kt"
private val COMMIT = CommitId.of("0123456789abcdef0123456789abcdef01234567")

/**
 * diff 조회 UseCase. Gateway 호출과 결과 전달만 하는 얇은 계층이라
 * 실패를 자체 상태로 바꾸지 않고 그대로 올린다 (architecture-layers 규칙 4).
 */
class LoadFileDiffUseCaseSpec : BehaviorSpec({

    Given("파일 하나의 diff 를 요청하면") {
        val gateway = mockk<DiffGateway>()
        val useCase = LoadFileDiffUseCase(gateway)

        When("gateway 가 hunk 를 계산해 주면") {
            val computed = DiffResult.Computed(
                listOf(DiffHunk(oldStart = 1, oldLineCount = 1, newStart = 1, newLineCount = 1, lines = emptyList())),
            )
            coEvery { gateway.hunksOf(COMMIT, PATH, 0) } returns computed

            Then("그 결과를 그대로 돌려준다") {
                useCase.execute(COMMIT, PATH) shouldBe computed
                coVerify(exactly = 1) { gateway.hunksOf(COMMIT, PATH, 0) }
            }
        }

        When("부모 인덱스를 지정하지 않으면") {
            coEvery { gateway.hunksOf(COMMIT, PATH, any()) } returns DiffResult.Computed(emptyList())

            Then("병합 커밋의 첫 부모를 기준으로 조회한다") {
                useCase.execute(COMMIT, PATH)
                coVerify { gateway.hunksOf(COMMIT, PATH, FIRST_PARENT_INDEX) }
            }
        }

        When("gateway 가 계산하지 않은 사유를 주면") {
            val notComputed = DiffResult.NotComputed(DiffResult.Reason.BINARY)
            coEvery { gateway.hunksOf(COMMIT, PATH, 1) } returns notComputed

            Then("사유를 잃지 않고 그대로 전달한다") {
                useCase.execute(COMMIT, PATH, parentIndex = 1) shouldBe notComputed
            }
        }

        When("조회가 실패하면") {
            coEvery { gateway.hunksOf(COMMIT, PATH, 0) } throws UndineException.NotFound(
                kind = UndineException.NotFound.Kind.COMMIT,
                name = COMMIT.value,
            )

            Then("도메인 예외를 삼키지 않고 올린다") {
                shouldThrow<UndineException.NotFound> { useCase.execute(COMMIT, PATH) }
            }
        }

        When("없는 부모 인덱스를 넘기면") {
            coEvery { gateway.hunksOf(COMMIT, PATH, 5) } throws IllegalArgumentException("parentIndex 5")

            Then("gateway 가 던진 IllegalArgumentException 이 그대로 전달된다") {
                shouldThrow<IllegalArgumentException> { useCase.execute(COMMIT, PATH, parentIndex = 5) }
            }
        }
    }
})
