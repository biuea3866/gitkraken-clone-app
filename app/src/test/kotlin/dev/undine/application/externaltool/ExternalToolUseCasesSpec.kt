package dev.undine.application.externaltool

import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.DiffToolResult
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.externaltool.ExternalToolUnavailable
import dev.undine.domain.externaltool.MergeToolInput
import dev.undine.domain.externaltool.MergeToolResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException

private const val LOCAL = "local 쪽 내용"
private const val REMOTE = "remote 쪽 내용"
private const val BASE = "공통 조상 내용"
private const val MERGED = "<<<<<<< 충돌 표식이 든 내용"
private const val RESOLVED = "사용자가 고친 내용"
private const val LAUNCH_FAILURE = "임시 파일을 만들지 못했습니다"

private val DIFF_INPUT = DiffToolInput(local = LOCAL, remote = REMOTE)

private val MERGE_INPUT = MergeToolInput(
    local = LOCAL,
    remote = REMOTE,
    base = BASE,
    merged = MERGED,
)

/**
 * 외부 도구 UseCase — **얇은 위임**이라 검증 대상은 "입력을 그대로 넘기고 결과·실패를 가공하지
 * 않는다" 는 사실이다. 도구 결정·프로세스 실행·임시 파일 정리는 Gateway 구현이 한다.
 */
class ExternalToolUseCasesSpec : BehaviorSpec({

    Given("diff 도구가 정상 종료하는 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openDiff(DIFF_INPUT) } returns DiffToolResult.Completed

        When("diff 실행을 요청하면") {
            val result = runBlocking { OpenDiffToolUseCase(gateway).execute(DIFF_INPUT) }

            Then("입력을 한 번만 Gateway 로 넘기고 결과를 그대로 돌려준다") {
                result shouldBe DiffToolResult.Completed
                coVerify(exactly = 1) { gateway.openDiff(DIFF_INPUT) }
                confirmVerified(gateway)
            }
        }
    }

    Given("도구가 0 이 아닌 코드로 끝나는 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openDiff(DIFF_INPUT) } returns DiffToolResult.ToolFailed(exitCode = 2)

        When("diff 실행을 요청하면") {
            val result = runBlocking { OpenDiffToolUseCase(gateway).execute(DIFF_INPUT) }

            Then("실패를 성공으로 접지 않고 종료 코드를 그대로 올린다") {
                result shouldBe DiffToolResult.ToolFailed(exitCode = 2)
            }
        }
    }

    Given("도구가 설정되지 않은 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openDiff(DIFF_INPUT) } returns ExternalToolUnavailable.NoToolConfigured

        When("diff 실행을 요청하면") {
            val result = runBlocking { OpenDiffToolUseCase(gateway).execute(DIFF_INPUT) }

            Then("대체 동작을 고르지 않고 사유를 그대로 돌려준다") {
                result shouldBe ExternalToolUnavailable.NoToolConfigured
            }
        }
    }

    Given("merge 도구가 편집 결과를 저장한 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openMerge(MERGE_INPUT) } returns MergeToolResult.Resolved(RESOLVED)

        When("merge 실행을 요청하면") {
            val result = runBlocking { OpenMergeToolUseCase(gateway).execute(MERGE_INPUT) }

            Then("3-way 입력을 한 번만 넘기고 편집 내용을 그대로 돌려준다") {
                result shouldBe MergeToolResult.Resolved(RESOLVED)
                coVerify(exactly = 1) { gateway.openMerge(MERGE_INPUT) }
                confirmVerified(gateway)
            }
        }
    }

    Given("merge 도구가 저장 없이 닫힌 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openMerge(MERGE_INPUT) } returns MergeToolResult.Unchanged

        When("merge 실행을 요청하면") {
            val result = runBlocking { OpenMergeToolUseCase(gateway).execute(MERGE_INPUT) }

            Then("빈 결과로 바꾸지 않고 변경 없음을 그대로 돌려준다") {
                result shouldBe MergeToolResult.Unchanged
            }
        }
    }

    Given("도구를 띄우다 입출력이 실패하는 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openMerge(MERGE_INPUT) } throws IOException(LAUNCH_FAILURE)

        When("merge 실행을 요청하면") {
            Then("예외를 삼키지 않고 그대로 올린다") {
                shouldThrow<IOException> {
                    runBlocking { OpenMergeToolUseCase(gateway).execute(MERGE_INPUT) }
                }.message shouldBe LAUNCH_FAILURE
            }
        }
    }

    Given("대기 중 호출자가 취소한 상태") {
        val gateway = mockk<ExternalToolGateway>()
        coEvery { gateway.openDiff(DIFF_INPUT) } throws CancellationException()

        When("diff 실행을 요청하면") {
            Then("취소를 결과로 접지 않고 그대로 전파한다") {
                shouldThrow<CancellationException> {
                    runBlocking { OpenDiffToolUseCase(gateway).execute(DIFF_INPUT) }
                }
            }
        }
    }
})
