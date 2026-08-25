package dev.undine.domain.identity

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.Commit
import dev.undine.domain.IdentityProfile
import dev.undine.domain.Person
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.testsupport.HistoryRequest
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant

private const val WORK_PROFILE = "회사"
private const val WORK_EMAIL = "me@work.example"
private const val OTHER_EMAIL = "me@personal.example"

private val FIXED_TIME = Instant.parse("2026-01-01T12:00:00Z")

private val WORK = IdentityProfile(
    name = WORK_PROFILE,
    email = WORK_EMAIL,
    signingKeyId = null,
    defaultAuthentication = AuthenticationMethod.SSH,
    expectedHost = null,
)

/**
 * 커밋 전 검사 규칙 — Gateway 대역으로 **규칙만** 검증한다.
 *
 * 실제 저장소 config·원격 읽기는 `IdentityGatewayImplSpec` 이 임시 저장소로 검증한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1·3).
 */
class IdentityServiceSpec : BehaviorSpec({

    Given("저장소에 프로필이 지정되지 않은 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.assignedProfileName() } returns null
        coEvery { gateway.profiles() } returns listOf(WORK)
        val history = RecordingHistoryGateway()

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, history).checkBeforeCommit()

            Then("미지정 경고만 돌려주고 이력은 읽지 않는다") {
                warnings shouldContainExactly listOf(IdentityWarning.ProfileNotAssigned)
                history.requests.shouldBeEmpty()
            }
        }
    }

    Given("로컬 설정이 이미 삭제된 프로필 이름을 가리키는 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.assignedProfileName() } returns "사라진 프로필"
        coEvery { gateway.profiles() } returns listOf(WORK)

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("새 실패 상태를 만들지 않고 미지정으로 취급한다") {
                warnings shouldContainExactly listOf(IdentityWarning.ProfileNotAssigned)
            }
        }
    }

    Given("프로필이 한 건도 저장되지 않은 상태") {
        val gateway = mockk<IdentityGateway>()
        coEvery { gateway.profiles() } returns emptyList()
        coEvery { gateway.assignedProfileName() } returns null
        val service = IdentityService(gateway, RecordingHistoryGateway())

        When("목록을 읽고 커밋 전 검사를 하면") {
            val profiles = service.profiles()
            val warnings = service.checkBeforeCommit()

            Then("빈 목록을 돌려주되 미지정 경고 경로는 그대로 동작한다") {
                profiles.shouldBeEmpty()
                warnings shouldContainExactly listOf(IdentityWarning.ProfileNotAssigned)
            }
        }
    }

    Given("예상 호스트가 없는 프로필이 지정된 저장소") {
        val gateway = assignedGateway(WORK)

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("호스트 경고를 건너뛰고 원격을 읽지도 않는다") {
                warnings.shouldBeEmpty()
                coVerify(exactly = 0) { gateway.remoteHost() }
            }
        }
    }

    Given("예상 호스트가 있지만 원격 호스트를 판단할 수 없는 저장소") {
        val gateway = assignedGateway(WORK.copy(expectedHost = "github.com"), remoteHost = null)

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("경고를 실패로 만들지 않고 생략한다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("예상 호스트가 지원 형식으로 파싱되지 않는 프로필") {
        val gateway = assignedGateway(WORK.copy(expectedHost = "/tmp/사내-서버"), remoteHost = "github.com")

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("호스트 경고를 생략한다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("원격 호스트가 예상 호스트와 다른 저장소") {
        val gateway = assignedGateway(
            WORK.copy(expectedHost = "git@Company.example:22"),
            remoteHost = "github.com",
        )

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("정규화한 두 호스트를 담아 불일치를 알린다") {
                warnings shouldContainExactly listOf(
                    IdentityWarning.HostMismatch(expectedHost = "company.example", remoteHost = "github.com"),
                )
            }
        }
    }

    Given("포트·userinfo·대소문자만 다른 같은 호스트") {
        val gateway = assignedGateway(
            WORK.copy(expectedHost = "GitHub.com"),
            remoteHost = "github.com",
        )

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, RecordingHistoryGateway()).checkBeforeCommit()

            Then("같은 호스트로 보고 경고하지 않는다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("다른 author 이메일로 쌓인 커밋이 있는 저장소") {
        val gateway = assignedGateway(WORK)
        val history = RecordingHistoryGateway(
            listOf(commitBy(1, authorEmail = OTHER_EMAIL), commitBy(2, authorEmail = WORK_EMAIL)),
        )

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, history).checkBeforeCommit()

            Then("HEAD 기준 최근 50건만 읽고 불일치를 알린다") {
                warnings shouldContainExactly listOf(
                    IdentityWarning.EmailMismatch(profileEmail = WORK_EMAIL, otherEmails = listOf(OTHER_EMAIL)),
                )
                history.requests shouldContainExactly listOf(
                    HistoryRequest(refs = listOf(RefName("HEAD")), offset = 0, limit = 50),
                )
            }
        }
    }

    Given("최근 50건 **밖에** 다른 이메일이 있는 저장소") {
        val gateway = assignedGateway(WORK)
        val commits = List(50) { index -> commitBy(index + 1, authorEmail = WORK_EMAIL) } +
            commitBy(51, authorEmail = OTHER_EMAIL)
        val history = RecordingHistoryGateway(commits)

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, history).checkBeforeCommit()

            Then("조회 범위 밖의 이력은 경고하지 않는다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("committer 만 다른 커밋이 있는 저장소") {
        val gateway = assignedGateway(WORK)
        val history = RecordingHistoryGateway(
            listOf(commitBy(1, authorEmail = WORK_EMAIL, committerEmail = OTHER_EMAIL)),
        )

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, history).checkBeforeCommit()

            Then("rebase·cherry-pick 이력을 오경고하지 않는다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("커밋이 하나도 없어 HEAD 를 풀 수 없는 저장소") {
        val gateway = assignedGateway(WORK)
        val history = RecordingHistoryGateway(
            failure = UndineException.NotFound(UndineException.NotFound.Kind.REF, "HEAD"),
        )

        When("커밋 전 검사를 하면") {
            val warnings = IdentityService(gateway, history).checkBeforeCommit()

            Then("비교할 이력이 없다는 뜻이므로 첫 커밋 전 검사가 실패하지 않는다") {
                warnings.shouldBeEmpty()
            }
        }
    }

    Given("이력 조회가 Git 연산 실패로 끝나는 저장소") {
        val gateway = assignedGateway(WORK)
        val history = RecordingHistoryGateway(failure = UndineException.GitOperationFailed("history.load"))

        When("커밋 전 검사를 하면") {
            val failure = runCatching { IdentityService(gateway, history).checkBeforeCommit() }

            Then("조용히 삼키지 않고 그대로 올린다") {
                failure.isFailure shouldBe true
            }
        }
    }
})

private fun assignedGateway(profile: IdentityProfile, remoteHost: String? = null): IdentityGateway {
    val gateway = mockk<IdentityGateway>()
    coEvery { gateway.assignedProfileName() } returns profile.name
    coEvery { gateway.profiles() } returns listOf(profile)
    coEvery { gateway.remoteHost() } returns remoteHost
    return gateway
}

private fun commitBy(seed: Int, authorEmail: String, committerEmail: String = authorEmail): Commit = Commit(
    id = commitId(seed),
    parents = emptyList(),
    message = "커밋 $seed",
    author = Person(name = "누군가", email = authorEmail),
    committer = Person(name = "누군가", email = committerEmail),
    authoredAt = FIXED_TIME,
    committedAt = FIXED_TIME,
)
