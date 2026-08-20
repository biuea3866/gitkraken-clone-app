package dev.undine.infrastructure.git.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val SECRET = "ghp_supersecrettoken"
private const val HTTPS_URL = "https://undine:$SECRET@github.com/undine/undine.git"
private val ORIGIN = RemoteIdentity(label = "origin", url = HTTPS_URL)

class RemoteUrlMaskingSpec : FunSpec({

    test("URL 을 자격증명·호스트·경로까지 통째로 원격 이름으로 바꾼다") {
        RemoteUrlMasking.mask(HTTPS_URL, ORIGIN) shouldBe "origin"
    }

    test("문장 안에 섞인 URL 도 남기지 않는다") {
        val masked = RemoteUrlMasking.mask("$HTTPS_URL: not authorized", ORIGIN)

        masked shouldBe "origin: not authorized"
        masked shouldNotContain SECRET
        masked shouldNotContain "github.com"
    }

    test("원본 URL 을 모르는 실패도 URL 형태를 보고 지운다") {
        val unknown = RemoteIdentity(label = "upstream", url = null)

        RemoteUrlMasking.mask("cannot open https://internal.example.com/team/app.git", unknown) shouldBe
            "cannot open upstream"
    }

    test("scp 형식 SSH 주소도 지운다") {
        val unknown = RemoteIdentity(label = "origin", url = null)

        RemoteUrlMasking.mask("auth fail for git@github.com:undine/undine.git", unknown) shouldBe
            "auth fail for origin"
    }

    test("로컬 파일 경로 원격도 알고 있는 URL 로 지운다") {
        val local = RemoteIdentity(label = "origin", url = "/tmp/undine/origin.git")

        RemoteUrlMasking.mask("/tmp/undine/origin.git: not found", local) shouldBe "origin: not found"
    }

    test("메시지가 없으면 빈 문자열이 된다") {
        RemoteUrlMasking.mask(null, ORIGIN) shouldBe ""
    }
})
