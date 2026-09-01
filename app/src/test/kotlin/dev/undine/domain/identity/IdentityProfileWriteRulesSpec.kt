package dev.undine.domain.identity

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.IdentityProfile
import dev.undine.domain.UndineException
import dev.undine.testsupport.RecordingHistoryGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk

private const val WORK_PROFILE = "회사"
private const val WORK_EMAIL = "me@work.example"

private val WORK = IdentityProfile(
    name = WORK_PROFILE,
    email = WORK_EMAIL,
    signingKeyId = "ABCD1234",
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = null,
)

/** 형식만 본다 — 실재하지 않는 주소여도 형식이 맞으면 통과해야 한다. */
private val VALID_EMAILS = listOf(
    "me@work.example",
    "first.last+tag@sub.example.co.kr",
    "a@b.cd",
)

private val INVALID_EMAILS = listOf(
    "",
    "   ",
    "notanemail",
    "no-at-sign.example",
    "me@",
    "@example.com",
    "me@localhost",
    "me me@example.com",
    "me@exa mple.com",
    "<me@example.com>",
    "me@@example.com",
    "me@example.",
)

/**
 * 프로필 **쓰기 경로**의 도메인 규칙 (UND-76).
 *
 * 이메일 형식 판단은 domain 이 소유한다 — presentation 에 두면 진입점이 늘 때마다 같은 규칙을
 * 다시 쓰게 되고 한 곳만 틀려도 조용히 통과한다. 읽기(`profiles()`)는 이 규칙에 걸리지 않는다:
 * 이미 저장된 잘못된 이메일이 앱을 못 열게 만드는 검증은 검증이 아니라 잠금이다.
 */
class IdentityProfileWriteRulesSpec : BehaviorSpec({

    Given("형식이 올바른 이메일들") {
        When("형식을 판정하면") {
            Then("전부 통과한다") {
                VALID_EMAILS.forEach { email -> isValidEmailFormat(email) shouldBe true }
            }
        }
    }

    Given("형식이 잘못된 이메일들") {
        When("형식을 판정하면") {
            Then("전부 거부된다") {
                INVALID_EMAILS.forEach { email -> isValidEmailFormat(email) shouldBe false }
            }
        }
    }

    Given("형식이 잘못된 이메일을 담은 새 프로필") {
        val gateway = mockk<IdentityGateway>(relaxed = true)
        val service = IdentityService(gateway, RecordingHistoryGateway())

        When("저장하면") {
            val failure = shouldThrow<UndineException.StateViolation> {
                service.saveProfile(WORK.copy(email = "notanemail"))
            }

            Then("거부하고 Gateway 까지 내려보내지 않는다") {
                failure.detail shouldBe "이메일 형식이 올바르지 않습니다: 'notanemail'"
                coVerify(exactly = 0) { gateway.saveProfile(any()) }
            }
        }
    }

    Given("형식이 잘못된 이메일로 바꾸려는 수정 요청") {
        val gateway = mockk<IdentityGateway>(relaxed = true)
        val service = IdentityService(gateway, RecordingHistoryGateway())

        When("수정하면") {
            shouldThrow<UndineException.StateViolation> {
                service.updateProfile(WORK_PROFILE, WORK.copy(email = "me@"))
            }

            Then("거부하고 Gateway 까지 내려보내지 않는다") {
                coVerify(exactly = 0) { gateway.updateProfile(any(), any()) }
            }
        }
    }

    Given("이미 저장된 잘못된 이메일을 가진 프로필") {
        val stored = WORK.copy(email = "예전에 저장된 값")
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.profiles() } returns listOf(stored)

        When("목록을 읽으면") {
            val profiles = IdentityService(gateway, RecordingHistoryGateway()).profiles()

            Then("읽기에서는 거부되지 않는다") {
                profiles shouldBe listOf(stored)
            }
        }
    }

    Given("형식이 올바른 이메일로 이름을 유지한 채 수정하는 요청") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.updateProfile(any(), any()) } just Runs
        val updated = WORK.copy(email = "me@new.example", signingKeyId = "FFFF9999")

        When("수정하면") {
            IdentityService(gateway, RecordingHistoryGateway()).updateProfile(WORK_PROFILE, updated)

            Then("delete + save 조합이 아니라 하나의 update 로 내려간다") {
                coVerify(exactly = 1) { gateway.updateProfile(WORK_PROFILE, updated) }
                coVerify(exactly = 0) { gateway.deleteProfile(any()) }
                coVerify(exactly = 0) { gateway.saveProfile(any()) }
            }
        }
    }
})
