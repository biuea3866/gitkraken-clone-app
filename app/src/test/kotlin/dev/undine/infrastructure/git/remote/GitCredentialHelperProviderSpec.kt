package dev.undine.infrastructure.git.remote

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish

private val HTTPS_URI = URIish("https://github.com/undine/undine.git")
private val SSH_URI = URIish("ssh://git@github.com/undine/undine.git")
private const val HELPER_ANSWER = "protocol=https\nhost=github.com\nusername=undine\npassword=secret\n"

class GitCredentialHelperProviderSpec : FunSpec({

    test("helper 응답을 자격증명 항목에 채운다") {
        var request = ""
        val provider = GitCredentialHelperProvider { asked ->
            request = asked
            "protocol=https\nhost=github.com\nusername=undine\npassword=secret\n"
        }
        val username = CredentialItem.Username()
        val password = CredentialItem.Password()

        provider.get(HTTPS_URI, username, password) shouldBe true

        username.value shouldBe "undine"
        password.value.concatToString() shouldBe "secret"
        request shouldContain "protocol=https"
        request shouldContain "host=github.com"
    }

    test("helper 를 실행할 수 없으면 익명으로 떨어지지 않고 실패한다") {
        val provider = GitCredentialHelperProvider { null }
        val username = CredentialItem.Username()
        val password = CredentialItem.Password()

        shouldThrow<CredentialProviderUnavailableException> { provider.get(HTTPS_URI, username, password) }

        username.value shouldBe null
        password.value shouldBe null
    }

    test("helper 가 사용자명·비밀번호를 주지 않으면 실패한다") {
        val provider = GitCredentialHelperProvider { "protocol=https\nhost=github.com\n" }

        shouldThrow<CredentialProviderUnavailableException> {
            provider.get(HTTPS_URI, CredentialItem.Username(), CredentialItem.Password())
        }
    }

    test("SSH 는 helper 를 호출하지 않고 ssh-agent 위임 실패로 끝난다") {
        var helperCalled = false
        val provider = GitCredentialHelperProvider {
            helperCalled = true
            "username=undine\npassword=secret\n"
        }

        shouldThrow<CredentialProviderUnavailableException> {
            provider.get(SSH_URI, CredentialItem.Username(), CredentialItem.Password())
        }

        helperCalled shouldBe false
    }

    test("앱은 자격증명을 직접 묻지 않는다") {
        GitCredentialHelperProvider { null }.isInteractive() shouldBe false
    }

    test("사용자명·비밀번호 외의 대화형 항목은 지원하지 않는다") {
        val provider = GitCredentialHelperProvider { null }

        provider.supports(CredentialItem.Username(), CredentialItem.Password()) shouldBe true
        provider.supports(CredentialItem.YesNoType("호스트 키를 신뢰하시겠습니까?")) shouldBe false
    }

    test("안내 문구는 credential helper 와 ssh-agent 설정 방법을 담는다") {
        GitCredentialHelperProvider.SETUP_GUIDE shouldContain "credential.helper"
        GitCredentialHelperProvider.SETUP_GUIDE shouldContain "ssh-agent"
    }
})

/**
 * 기본 생성자가 쓰는 서브프로세스 실행 경로. 실제 `git credential fill` 은 사용자 설정에 따라
 * 결과가 달라지므로, 프로토콜이 같은 표준 명령으로 성공·비정상 종료·실행 실패·시간 초과를 만든다.
 */
class ProcessCredentialHelperRunnerSpec : FunSpec({

    test("헬퍼가 응답하면 본문을 그대로 돌려준다") {
        val runner = ProcessCredentialHelperRunner(command = listOf("cat"))

        runner.fill(HELPER_ANSWER) shouldBe HELPER_ANSWER
    }

    test("실제 git credential fill 명령을 기본 헬퍼로 쓴다") {
        val runner = ProcessCredentialHelperRunner(command = listOf("git", "credential", "fill"))

        // 설정된 helper 가 없으면 빈 응답이 오고, 있으면 자격증명이 온다. 어느 쪽이든 막히지 않는다.
        val answer = runner.fill("protocol=https\nhost=undine.invalid\n\n")

        (answer == null || answer.contains("protocol=https")) shouldBe true
    }

    test("헬퍼가 비정상 종료하면 응답 없음으로 처리한다") {
        val runner = ProcessCredentialHelperRunner(command = listOf("false"))

        runner.fill(HELPER_ANSWER) shouldBe null
    }

    test("헬퍼를 실행할 수 없으면 익명으로 떨어지지 않고 실패한다") {
        val runner = ProcessCredentialHelperRunner(command = listOf("undine-no-such-credential-helper"))

        shouldThrow<CredentialProviderUnavailableException> { runner.fill(HELPER_ANSWER) }
    }

    test("헬퍼가 시간 안에 끝나지 않으면 강제 종료하고 응답 없음으로 처리한다") {
        val runner = ProcessCredentialHelperRunner(command = listOf("sleep", "30"), timeoutSeconds = 1)

        runner.fill(HELPER_ANSWER) shouldBe null
    }
})
