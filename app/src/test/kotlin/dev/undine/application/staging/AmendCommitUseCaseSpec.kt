package dev.undine.application.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.AmendPreflight
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.domain.undo.UndoStack
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.recorderOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk

private const val TARGET_HASH = "1111111111111111111111111111111111111111"
private const val AMENDED_HASH = "3333333333333333333333333333333333333333"
private const val MESSAGE = "커밋 메시지 고침"

private val TARGET = CommitId.of(TARGET_HASH)
private val PREVIOUS = CommitId.of(TARGET_HASH)
private val AMENDED = CommitResult(
    commitId = CommitId.of(AMENDED_HASH),
    previousHead = PREVIOUS,
    baseline = baselineOf(CommitId.of(AMENDED_HASH)),
)

class AmendCommitUseCaseSpec : BehaviorSpec({

    Given("amend 대상이 원격에 없는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.inspectAmend() } returns AmendPreflight(TARGET, existsOnRemote = false)
        coEvery { gateway.amend(MESSAGE, AmendConfirmation.NotRequired) } returns AMENDED

        When("amend 를 요청하면") {
            val outcome = gateway.useCase().request(MESSAGE)

            Then("조회 뒤 확인 없이 곧바로 실행한다") {
                outcome.shouldBeInstanceOf<AmendOutcome.Amended>().outcome.result shouldBe AMENDED
                coVerifyOrder {
                    gateway.inspectAmend()
                    gateway.amend(MESSAGE, AmendConfirmation.NotRequired)
                }
            }
        }
    }

    Given("amend 대상이 원격에 있는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.inspectAmend() } returns AmendPreflight(TARGET, existsOnRemote = true)
        coEvery { gateway.amend(any(), any()) } returns AMENDED
        val useCase = gateway.useCase()

        When("amend 를 요청하면") {
            val outcome = useCase.request(MESSAGE)

            Then("실행하지 않고 조회한 대상에 대한 확인을 요구한다") {
                outcome.shouldBeInstanceOf<AmendOutcome.ConfirmationRequired>().target shouldBe TARGET
                coVerify(exactly = 0) { gateway.amend(any(), any()) }
            }
        }

        When("사용자가 그 대상을 확인하면") {
            val result = useCase.confirm(MESSAGE, TARGET)

            Then("같은 대상에 대한 확인 값으로 실행한다") {
                result.result shouldBe AMENDED
                coVerify { gateway.amend(MESSAGE, AmendConfirmation.ConfirmedRemoteTarget(TARGET)) }
            }
        }
    }

    Given("Gateway 가 조회 단계에서 실패하는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.inspectAmend() } throws UndineException.StateViolation("고칠 이전 커밋이 없습니다")
        coEvery { gateway.amend(any(), any()) } returns AMENDED

        When("amend 를 요청하면") {
            Then("실패를 삼키지 않고 그대로 올리며 실행하지 않는다") {
                shouldThrow<UndineException.StateViolation> { gateway.useCase().request(MESSAGE) }
                coVerify(exactly = 0) { gateway.amend(any(), any()) }
            }
        }
    }

    Given("Gateway 가 실행 단계에서 확인을 거부하는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.inspectAmend() } returns AmendPreflight(TARGET, existsOnRemote = false)
        coEvery { gateway.amend(any(), any()) } throws UndineException.AmendConfirmationRequired(
            target = TARGET,
            reason = UndineException.AmendConfirmationRequired.Reason.NOT_CONFIRMED,
        )

        When("amend 를 요청하면") {
            Then("성공 상태로 바꾸지 않고 실패를 올린다") {
                shouldThrow<UndineException.AmendConfirmationRequired> { gateway.useCase().request(MESSAGE) }
            }
        }
    }

    Given("확인 뒤 Gateway 가 실행을 거부하는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.inspectAmend() } returns AmendPreflight(TARGET, existsOnRemote = true)
        coEvery { gateway.amend(any(), any()) } throws UndineException.AmendConfirmationRequired(
            target = TARGET,
            reason = UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH,
        )

        When("사용자 확인을 받아 confirm 을 부르면") {
            Then("실패를 삼키거나 성공 결과로 바꾸지 않고 그대로 올린다") {
                val failure = shouldThrow<UndineException.AmendConfirmationRequired> {
                    gateway.useCase().confirm(MESSAGE, TARGET)
                }
                failure.reason shouldBe UndineException.AmendConfirmationRequired.Reason.TARGET_MISMATCH
                failure.target shouldBe TARGET
            }
        }
    }

    Given("확인 뒤 Gateway 가 백업 실패로 거부하는 저장소") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.amend(any(), any()) } throws
            UndineException.StateViolation("amend 대상을 백업하지 못해 커밋을 고치지 않았습니다")

        When("사용자 확인을 받아 confirm 을 부르면") {
            Then("StateViolation 을 그대로 올린다") {
                shouldThrow<UndineException.StateViolation> { gateway.useCase().confirm(MESSAGE, TARGET) }
                coVerify(exactly = 1) {
                    gateway.amend(MESSAGE, AmendConfirmation.ConfirmedRemoteTarget(TARGET))
                }
            }
        }
    }

    Given("일반 커밋 경로") {
        val gateway = mockk<StagingGateway>()
        coEvery { gateway.commit(MESSAGE) } returns AMENDED
        coEvery { gateway.stage(any()) } just Runs

        When("커밋하면") {
            val result = gateway.commit(MESSAGE)

            Then("확인 값을 요구하지 않고 amend 조회도 하지 않는다") {
                result shouldBe AMENDED
                coVerify(exactly = 0) { gateway.inspectAmend() }
            }
        }
    }
})

private fun StagingGateway.useCase(): AmendCommitUseCase =
    AmendCommitUseCase(this, recorderOf(UndoStack()))
