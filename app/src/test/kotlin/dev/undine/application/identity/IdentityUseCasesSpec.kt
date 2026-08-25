package dev.undine.application.identity

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.IdentityProfile
import dev.undine.domain.UndineException
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.IdentityService
import dev.undine.domain.identity.IdentityWarning
import dev.undine.testsupport.RecordingHistoryGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk

private const val WORK_PROFILE = "회사"
private const val DUPLICATE = "이미 있는 이름입니다"

private val WORK = IdentityProfile(
    name = WORK_PROFILE,
    email = "me@work.example",
    signingKeyId = "ABCD1234",
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = null,
)

/**
 * identity UseCase — **얇은 위임**이라 검증 대상은 "가공하지 않고 그대로 전한다" 는 사실이다.
 * 규칙 판단은 `IdentityService` 가, Git·설정 접근은 Gateway 구현이 한다.
 */
class IdentityUseCasesSpec : BehaviorSpec({

    Given("프로필이 저장된 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.profiles() } returns listOf(WORK)
        val service = IdentityService(gateway, RecordingHistoryGateway())

        When("목록을 요청하면") {
            val profiles = LoadProfilesUseCase(service).execute()

            Then("서명 키 ID 를 포함한 프로필을 그대로 돌려준다") {
                profiles shouldContainExactly listOf(WORK)
            }
        }
    }

    Given("프로필이 한 건도 없는 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.profiles() } returns emptyList()

        When("목록을 요청하면") {
            val profiles = LoadProfilesUseCase(IdentityService(gateway, RecordingHistoryGateway())).execute()

            Then("빈 목록을 돌려준다") {
                profiles.shouldBeEmpty()
            }
        }
    }

    Given("같은 이름의 프로필이 이미 있는 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.saveProfile(WORK) } throws UndineException.StateViolation(DUPLICATE)

        When("같은 이름으로 저장하면") {
            val service = IdentityService(gateway, RecordingHistoryGateway())

            Then("거부를 은폐하지 않고 그대로 올린다") {
                shouldThrow<UndineException.StateViolation> {
                    SaveProfileUseCase(service).execute(WORK)
                }.detail shouldBe DUPLICATE
            }
        }
    }

    Given("저장소를 연 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.applyProfile(WORK) } just Runs
        coEvery { gateway.clearLocalIdentity() } just Runs
        coEvery { gateway.deleteProfile(WORK_PROFILE) } just Runs
        val service = IdentityService(gateway, RecordingHistoryGateway())

        When("적용·삭제·로컬 설정 제거를 요청하면") {
            ApplyProfileUseCase(service).execute(WORK)
            DeleteProfileUseCase(service).execute(WORK_PROFILE)
            ClearLocalIdentityUseCase(service).execute()

            Then("각 요청을 Gateway 로 그대로 넘긴다") {
                coVerify(exactly = 1) { gateway.applyProfile(WORK) }
                coVerify(exactly = 1) { gateway.deleteProfile(WORK_PROFILE) }
                coVerify(exactly = 1) { gateway.clearLocalIdentity() }
            }
        }
    }

    Given("프로필이 지정되지 않은 저장소") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.assignedProfileName() } returns null
        coEvery { gateway.profiles() } returns emptyList()

        When("커밋 전 검사를 요청하면") {
            val service = IdentityService(gateway, RecordingHistoryGateway())
            val warnings = CheckIdentityBeforeCommitUseCase(service).execute()

            Then("경고 목록을 바꾸지 않고 그대로 돌려준다") {
                warnings shouldContainExactly listOf(IdentityWarning.ProfileNotAssigned)
            }
        }
    }
})
