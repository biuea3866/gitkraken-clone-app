package dev.undine.domain.identity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 호스트 정규화 — **순수 규칙**이라 저장소 없이 검증한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 3).
 *
 * 파싱 실패는 오류가 아니라 **경고 생략**이다. 여기서 null 이 나오는 입력은 호스트 경고를
 * 건너뛰는 입력이라는 뜻이다.
 */
class RemoteHostNameSpec : FunSpec({

    test("https URL 은 호스트만 남긴다") {
        normalizedHostOf("https://github.com/undine/undine.git") shouldBe "github.com"
    }

    test("ssh URL 의 userinfo 와 포트를 지운다") {
        normalizedHostOf("ssh://git@GitHub.com:22/undine/undine.git") shouldBe "github.com"
    }

    test("scp 형식도 받는다") {
        normalizedHostOf("git@github.com:undine/undine.git") shouldBe "github.com"
    }

    test("호스트만 적은 값은 그대로 정규화한다") {
        normalizedHostOf(" GitHub.com ") shouldBe "github.com"
    }

    test("로컬 경로 원격은 호스트가 없어 판단하지 않는다") {
        normalizedHostOf("/tmp/undine-origin") shouldBe null
        normalizedHostOf("file:///tmp/undine-origin") shouldBe null
    }

    test("빈 값과 호스트가 비어 있는 URL 은 판단하지 않는다") {
        normalizedHostOf("") shouldBe null
        normalizedHostOf("   ") shouldBe null
        normalizedHostOf("https:///undine.git") shouldBe null
        normalizedHostOf("git@:undine.git") shouldBe null
    }
})
