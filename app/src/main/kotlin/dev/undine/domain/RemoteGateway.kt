package dev.undine.domain

/**
 * 원격 연동. 진행률은 [Progress] 콜백으로 올린다 — 초 단위 작업이 무반응으로 보이지 않게 한다.
 * 인증 실패는 [UndineException.AuthenticationFailed] 로 번역하고 원격 URL 토큰을 남기지 않는다.
 */
interface RemoteGateway {

    /**
     * 등록된 원격 이름. 툴바가 어느 원격으로 fetch·pull 할지 정하는 재료다.
     *
     * 이름만 준다 — URL 은 자격증명이 섞일 수 있어 화면으로 내보내지 않는다
     * ([credential-handling] 2항). 원격이 없으면 빈 목록이며 오류가 아니다.
     */
    suspend fun listRemotes(): List<String>

    suspend fun clone(url: String, into: RepositoryPath, onProgress: (Progress) -> Unit)

    suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef>

    suspend fun pull(remote: String, onProgress: (Progress) -> Unit)

    /**
     * [ref] 를 [remote] 로 올린다.
     *
     * **원격을 게이트웨이가 추측하지 않는다.** 예전에는 현재 브랜치의 업스트림에서 풀었고,
     * 미는 대상이 현재 브랜치가 아니면 **동의한 곳과 다른 원격**으로 나갔다 — force push 면
     * 남의 이력을 덮어쓴다. 확인 문장이 말한 원격이 그대로 여기로 들어와야 그 확인이 근거를 갖는다.
     */
    suspend fun push(ref: RefName, remote: String, force: Boolean, onProgress: (Progress) -> Unit): PushResult
}
