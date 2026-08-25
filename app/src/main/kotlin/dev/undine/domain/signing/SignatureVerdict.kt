package dev.undine.domain.signing

/**
 * 기존 커밋·태그의 서명 검증 결과.
 *
 * **[NotSigned] 와 [Invalid] 를 뭉치지 않는다.** 둘을 합치면 위조된 서명이 "서명이 없는 커밋" 과
 * 같아 보여, 서명을 확인하려던 사용자가 정반대의 결론을 내린다.
 *
 * [Undetermined] 가 따로 있는 이유도 같다 — 검증 도구가 없거나 공개키가 없어 **판단하지 못한**
 * 것을 [Invalid] 로 보고하면 정상 서명을 위조로 알리게 되고, [Valid] 로 보고하면 검증하지 않은
 * 것을 검증했다고 말하게 된다.
 */
sealed interface SignatureVerdict {

    /** 서명이 없는 객체다. 실패가 아니라 정상적인 결과다 — 대부분의 커밋이 여기 해당한다. */
    data object NotSigned : SignatureVerdict

    /**
     * 서명이 있고 유효하다. [trust] 는 서명 자체의 유효성과 **별개 축**이다 ([TrustLevel]).
     *
     * @param signer 서명자로 확인된 사람. GPG 는 키의 사용자 ID, SSH 는 대조에 쓴 신원이다.
     */
    data class Valid(
        val format: SigningFormat,
        val signer: String,
        val trust: TrustLevel,
    ) : SignatureVerdict

    /** 서명이 있으나 유효하지 않다 — 내용이 변조됐거나 키가 만료·폐기됐다. */
    data class Invalid(val format: SigningFormat, val detail: String) : SignatureVerdict

    /**
     * 서명은 있지만 유효한지 판단하지 못했다.
     *
     * 조용히 [NotSigned] 나 [Invalid] 로 뭉개지 않는다 — 화면은 "확인할 수 없었다" 를 그대로
     * 알리고 사용자가 [Undetermined.Reason] 에 맞는 조치(도구 설치·공개키 가져오기)를 하게 한다.
     */
    data class Undetermined(val reason: Reason, val detail: String) : SignatureVerdict {

        enum class Reason {
            /** 검증에 쓸 외부 프로그램(`gpg`·`ssh-keygen`)이 없다. */
            PROGRAM_UNAVAILABLE,

            /** 서명자의 공개키를 찾을 수 없어 대조할 대상이 없다. */
            PUBLIC_KEY_UNAVAILABLE,

            /** 이 앱이 다루지 않는 서명 형식이다 ([SigningFormat.X509] 등). */
            UNSUPPORTED_FORMAT,

            /** 검증 프로그램이 알아볼 수 없는 방식으로 끝났다. */
            VERIFIER_FAILED,
        }
    }
}
