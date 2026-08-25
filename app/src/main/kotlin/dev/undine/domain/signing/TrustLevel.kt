package dev.undine.domain.signing

/**
 * 서명 키를 얼마나 신뢰하는가. GPG 의 표준 신뢰 등급을 그대로 쓴다.
 *
 * **서명 유효성과는 별개 축이다.** 암호학적으로 완벽히 유효한 서명이 전혀 신뢰하지 않는 키에서
 * 나올 수 있다 — 두 축을 하나로 합치면 "유효하지만 모르는 사람이 서명함" 을 표현할 수 없다.
 *
 * SSH 에는 이런 등급 개념이 없어 allowed_signers 매칭 여부를 [FULL]/[UNKNOWN] 에 대응시킨다.
 */
enum class TrustLevel {

    /** 신뢰 여부를 판단할 근거가 없다. SSH 에서는 allowed_signers 에 없는 서명자다. */
    UNKNOWN,

    /** 신뢰하지 않기로 명시된 키다. */
    NEVER,

    /** 부분적으로 신뢰한다. */
    MARGINAL,

    /** 신뢰한다. SSH 에서는 allowed_signers 에 등재된 서명자다. */
    FULL,

    /** 자신의 키처럼 완전히 신뢰한다. */
    ULTIMATE,
}
