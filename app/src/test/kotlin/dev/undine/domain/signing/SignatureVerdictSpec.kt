package dev.undine.domain.signing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 검증 결과 계약의 **구분**을 고정한다.
 *
 * 값 클래스 나열이 아니라 티켓이 못박은 두 가지를 지키는지 보는 테스트다 —
 * (1) '서명 없음 / 유효 / 유효하지 않음' 이 서로 다른 타입이라 뭉갤 수 없고,
 * (2) 신뢰 수준이 유효성과 **별개 축**이라 "유효하지만 신뢰하지 않는 키" 를 표현할 수 있다.
 */
class SignatureVerdictSpec : FunSpec({

    test("서명 없음과 유효하지 않음은 같은 값이 될 수 없다") {
        val notSigned: SignatureVerdict = SignatureVerdict.NotSigned
        val invalid: SignatureVerdict = SignatureVerdict.Invalid(SigningFormat.OPENPGP, "내용이 변조됨")

        notSigned shouldNotBe invalid
    }

    test("유효는 유효하지 않음·판단 불가와 구분된다") {
        val valid: SignatureVerdict = SignatureVerdict.Valid(SigningFormat.SSH, "user@example.invalid", TrustLevel.FULL)
        val invalid: SignatureVerdict = SignatureVerdict.Invalid(SigningFormat.SSH, "대조 실패")
        val undetermined: SignatureVerdict = SignatureVerdict.Undetermined(
            SignatureVerdict.Undetermined.Reason.PUBLIC_KEY_UNAVAILABLE,
            "공개키 없음",
        )

        setOf(valid, invalid, undetermined).size shouldBe 3
    }

    test("유효한 서명도 신뢰하지 않는 키에서 나올 수 있다 — 두 축은 독립이다") {
        val untrusted = SignatureVerdict.Valid(SigningFormat.OPENPGP, "낯선 사람", TrustLevel.UNKNOWN)
        val trusted = SignatureVerdict.Valid(SigningFormat.OPENPGP, "본인", TrustLevel.ULTIMATE)

        untrusted.trust shouldBe TrustLevel.UNKNOWN
        trusted.trust shouldBe TrustLevel.ULTIMATE
    }

    test("신뢰 등급은 GPG 표준 값을 그대로 쓴다") {
        TrustLevel.entries.map { it.name } shouldContainExactly
            listOf("UNKNOWN", "NEVER", "MARGINAL", "FULL", "ULTIMATE")
    }

    test("서명 실패 사유는 키 없음·도구 없음·거부·미지원 형식을 구분한다") {
        SignResult.Failed.Reason.entries.map { it.name } shouldContainExactly
            listOf("NO_SIGNING_KEY", "PROGRAM_UNAVAILABLE", "AGENT_REFUSED", "UNSUPPORTED_FORMAT")
    }

    test("서명 성공은 실패와 같은 값이 될 수 없다") {
        val signed: SignResult = SignResult.Signed("-----BEGIN PGP SIGNATURE-----")
        val failed: SignResult = SignResult.Failed(SignResult.Failed.Reason.AGENT_REFUSED, "agent 없음")

        signed shouldNotBe failed
    }
})
