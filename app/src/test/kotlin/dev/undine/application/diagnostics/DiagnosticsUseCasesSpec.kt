package dev.undine.application.diagnostics

import dev.undine.domain.diagnostics.DiagnosticsGateway
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.diagnostics.LogDirectoryMissing
import dev.undine.domain.diagnostics.OpenLogDirectoryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

private const val OPEN_FAILURE_REASON = "파일 관리자를 띄울 수 없습니다"

private val LOG_DIRECTORY: Path = Path.of("/home/user/.undine")

/**
 * 로그 디렉터리 UseCase — **얇은 위임**이라 검증 대상은 "결과와 실패를 가공하지 않는다" 는 사실이다.
 * 경로 판정과 파일 관리자 실행은 Gateway 구현이 한다.
 */
class DiagnosticsUseCasesSpec : BehaviorSpec({

    Given("로그 디렉터리가 있는 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.locateLogDirectory() } returns LogDirectoryLocation.Found(LOG_DIRECTORY)

        When("경로 조회를 요청하면") {
            val result = runBlocking { LocateLogDirectoryUseCase(gateway).execute() }

            Then("Gateway 를 한 번만 부르고 경로를 그대로 돌려준다") {
                result shouldBe LogDirectoryLocation.Found(LOG_DIRECTORY)
                coVerify(exactly = 1) { gateway.locateLogDirectory() }
                confirmVerified(gateway)
            }
        }
    }

    Given("로그 디렉터리가 아직 없는 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.locateLogDirectory() } returns LogDirectoryMissing

        When("경로 조회를 요청하면") {
            val result = runBlocking { LocateLogDirectoryUseCase(gateway).execute() }

            Then("실패가 아니라 '아직 없음' 을 그대로 돌려준다") {
                result shouldBe LogDirectoryMissing
            }
        }
    }

    Given("파일 관리자가 정상적으로 열리는 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.openLogDirectory() } returns OpenLogDirectoryResult.Opened

        When("열기를 요청하면") {
            val result = runBlocking { OpenLogDirectoryUseCase(gateway).execute() }

            Then("Gateway 를 한 번만 부르고 결과를 그대로 돌려준다") {
                result shouldBe OpenLogDirectoryResult.Opened
                coVerify(exactly = 1) { gateway.openLogDirectory() }
                confirmVerified(gateway)
            }
        }
    }

    Given("열기가 실패하는 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.openLogDirectory() } returns OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON)

        When("열기를 요청하면") {
            val result = runBlocking { OpenLogDirectoryUseCase(gateway).execute() }

            Then("실패 사유를 가공하지 않고 그대로 올린다") {
                result shouldBe OpenLogDirectoryResult.OpenFailed(OPEN_FAILURE_REASON)
            }
        }
    }

    Given("디렉터리가 없어 열 대상이 없는 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.openLogDirectory() } returns LogDirectoryMissing

        When("열기를 요청하면") {
            val result = runBlocking { OpenLogDirectoryUseCase(gateway).execute() }

            Then("조용한 성공이 아니라 '아직 없음' 이 올라온다") {
                result shouldBe LogDirectoryMissing
            }
        }
    }

    Given("열기 도중 호출자가 취소된 상태") {
        val gateway = mockk<DiagnosticsGateway>()
        coEvery { gateway.openLogDirectory() } throws CancellationException("취소")

        When("열기를 요청하면") {
            Then("취소를 삼키지 않고 그대로 전파한다") {
                shouldThrow<CancellationException> {
                    runBlocking { OpenLogDirectoryUseCase(gateway).execute() }
                }
            }
        }
    }
})
