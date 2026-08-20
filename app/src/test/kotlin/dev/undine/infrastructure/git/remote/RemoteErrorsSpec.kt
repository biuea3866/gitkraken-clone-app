package dev.undine.infrastructure.git.remote

import dev.undine.domain.UndineException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.errors.TransportException
import java.io.IOException

private const val SECRET = "ghp_supersecrettoken"
private const val HOST = "github.com"
private const val PATH = "undine/undine.git"
private const val REMOTE_URL = "https://undine:$SECRET@$HOST/$PATH"
private val ORIGIN = RemoteIdentity(label = "origin", url = REMOTE_URL)

class RemoteErrorsSpec : FunSpec({

    test("도메인 예외는 번역하지 않고 그대로 통과시킨다") {
        val original = UndineException.NotFound(UndineException.NotFound.Kind.REMOTE, "origin")

        RemoteErrors.translate("remote.fetch", ORIGIN, original) shouldBe original
    }

    test("자격증명 제공자 부재는 AuthenticationFailed 로 번역된다") {
        val cause = TransportException(
            REMOTE_URL,
            CredentialProviderUnavailableException("credential helper 를 실행할 수 없습니다."),
        )

        val translated = RemoteErrors.translate("remote.fetch", ORIGIN, cause)

        translated.shouldBeInstanceOf<UndineException.AuthenticationFailed>()
        translated.remote shouldBe "origin"
    }

    test("인증 거절 메시지는 AuthenticationFailed 로 번역된다") {
        val cause = TransportException("$REMOTE_URL: not authorized")

        RemoteErrors.translate("remote.push", ORIGIN, cause)
            .shouldBeInstanceOf<UndineException.AuthenticationFailed>()
    }

    test("번역된 예외에는 토큰도 호스트도 경로도 남지 않는다") {
        val cause = TransportException("$REMOTE_URL: not authorized")

        val translated = RemoteErrors.translate("remote.push", ORIGIN, cause)

        translated.stackTraceToString() shouldNotContain SECRET
        translated.stackTraceToString() shouldNotContain HOST
        translated.stackTraceToString() shouldNotContain PATH
        translated.message.orEmpty() shouldNotContain SECRET
        translated.message.orEmpty() shouldNotContain HOST
    }

    test("전송 실패 cause 에서도 원격 주소가 이름으로 치환된다") {
        val cause = TransportException("$REMOTE_URL: disk is gone")

        val translated = RemoteErrors.translate("remote.fetch", ORIGIN, cause)

        translated.cause?.message.orEmpty() shouldNotContain HOST
        translated.cause?.message.orEmpty() shouldNotContain SECRET
    }

    test("로컬 파일 경로 원격의 실패도 경로를 남기지 않는다") {
        val localRemote = RemoteIdentity(label = "origin", url = "/tmp/undine/origin.git")
        val cause = TransportException("/tmp/undine/origin.git: not found")

        val translated = RemoteErrors.translate("remote.fetch", localRemote, cause)

        translated.stackTraceToString() shouldNotContain "/tmp/undine/origin.git"
    }

    test("원인을 알 수 없는 실패는 GitOperationFailed 로 번역된다") {
        val translated = RemoteErrors.translate("remote.pull", ORIGIN, IOException("disk is gone"))

        translated.shouldBeInstanceOf<UndineException.GitOperationFailed>()
        translated.operation shouldBe "remote.pull"
    }
})
