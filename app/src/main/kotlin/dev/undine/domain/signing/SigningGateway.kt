package dev.undine.domain.signing

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException

/**
 * 커밋·태그 서명과 기존 서명 검증을 맡는 외부 Git 접근 계약. 구현은 `SigningGatewayImpl` 이다.
 *
 * **앱은 키를 관리하지 않는다.** 기존 `gpg-agent`/`ssh-agent` 와 git 설정(`user.signingkey`·
 * `gpg.format`)을 그대로 쓴다. 앱이 별도 키 저장소를 만들면 사용자가 이미 맞춰 둔 설정과 어긋난다.
 *
 * **패스프레이즈를 받는 자리가 이 계약에 없다.** 앱이 패스프레이즈를 받으면 그 순간 메모리에
 * 비밀이 생긴다 — 잠금 해제는 agent 소관이고, agent 가 없으면 [SignResult.Failed] 로 알린다.
 */
interface SigningGateway {

    /** 이 저장소의 서명 설정. 호출부가 "지금 서명해야 하는가" 를 판단하는 근거다. */
    suspend fun settings(): SigningSettings

    /**
     * [payload] 에 서명한다. [payload] 는 서명 대상 git 객체의 바이트다 — 커밋과 태그가 같은
     * 경로를 쓴다 (git 도 두 객체에 같은 서명 절차를 적용한다).
     *
     * 실패는 던지지 않고 [SignResult.Failed] 로 돌려준다. 호출부는 실패를 받으면 **객체를 만들지
     * 않아야** 한다 — 서명 없이 만들어지면 사용자는 서명된 줄 안다.
     */
    suspend fun sign(payload: ByteArray): SignResult

    /**
     * [commit] 의 서명을 검증한다.
     *
     * @throws UndineException.NotFound 커밋을 찾을 수 없을 때 — 서명이 없는 것과 대상이 없는 것은
     *   사용자가 취할 행동이 다르다.
     */
    suspend fun verifyCommit(commit: CommitId): SignatureVerdict

    /**
     * [tag] 의 서명을 검증한다. 경량 태그는 서명을 실을 객체가 없으므로
     * [SignatureVerdict.NotSigned] 다.
     *
     * @throws UndineException.NotFound 태그를 찾을 수 없을 때
     */
    suspend fun verifyTag(tag: RefName): SignatureVerdict
}
