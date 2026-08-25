package dev.undine.domain.signing

/**
 * 서명 요청의 결과.
 *
 * 실패를 예외가 아니라 결과 타입으로 두는 이유: agent 가 잠겨 있거나 도구가 없는 것은 **예상되는
 * 상황**이지 앱의 사고가 아니다. 그리고 호출부가 "서명이 없으면 커밋도 만들지 않는다" 를 지키려면
 * 실패를 정상 흐름에서 받아 분기할 수 있어야 한다.
 */
sealed interface SignResult {

    /** 서명에 성공했다. [signature] 는 git 객체에 그대로 실리는 armor 형식 문자열이다. */
    data class Signed(val signature: String) : SignResult

    /**
     * 서명하지 못했다. **호출부는 이 결과를 받으면 커밋·태그를 만들지 않아야 한다** —
     * 서명하려다 실패했는데 서명 없이 만들어지면 사용자는 서명된 줄 안다.
     */
    data class Failed(val reason: Reason, val detail: String) : SignResult {

        enum class Reason {
            /** 서명할 키가 설정돼 있지 않다 (`user.signingkey`). */
            NO_SIGNING_KEY,

            /** 서명에 쓸 외부 프로그램(`gpg`·`ssh-keygen`)이 없다. */
            PROGRAM_UNAVAILABLE,

            /**
             * 프로그램은 실행됐지만 서명을 거부했다.
             *
             * agent 가 떠 있지 않거나, 패스프레이즈 입력이 취소됐거나, 키를 열 수 없는 경우가 모두
             * 여기다 — 앱이 패스프레이즈를 대신 받지 않으므로 그 처리는 agent 소관이다.
             */
            AGENT_REFUSED,

            /** 이 앱이 다루지 않는 서명 형식이다 ([SigningFormat.X509] 등). */
            UNSUPPORTED_FORMAT,
        }
    }
}
