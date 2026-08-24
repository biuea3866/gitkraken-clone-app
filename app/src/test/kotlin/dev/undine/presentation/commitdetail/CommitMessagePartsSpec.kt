package dev.undine.presentation.commitdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 커밋 메시지의 제목/본문 분리 — 렌더링 없이 검증 가능한 순수 규칙이다. */
class CommitMessagePartsSpec : FunSpec({

    test("첫 줄이 제목이고 나머지가 본문이다") {
        val parts = CommitMessageParts.of("제목 줄\n\n본문 첫 줄\n본문 둘째 줄")

        parts.subject shouldBe "제목 줄"
        parts.body shouldBe "본문 첫 줄\n본문 둘째 줄"
        parts.hasBody shouldBe true
    }

    test("한 줄짜리 메시지는 본문이 비어 접을 것이 없다") {
        val parts = CommitMessageParts.of("한 줄 요약")

        parts.subject shouldBe "한 줄 요약"
        parts.body shouldBe ""
        parts.hasBody shouldBe false
    }

    test("CRLF 줄바꿈도 같은 기준으로 나뉜다") {
        val parts = CommitMessageParts.of("제목\r\n\r\n본문")

        parts.subject shouldBe "제목"
        parts.body shouldBe "본문"
    }

    test("본문만 공백인 메시지는 본문이 없는 것으로 본다") {
        val parts = CommitMessageParts.of("제목\n\n   \n\t\n")

        parts.subject shouldBe "제목"
        parts.hasBody shouldBe false
    }

    test("빈 메시지도 예외 없이 빈 제목과 빈 본문이 된다") {
        val parts = CommitMessageParts.of("")

        parts.subject shouldBe ""
        parts.hasBody shouldBe false
    }
})
