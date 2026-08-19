package dev.undine.domain

/**
 * 원격 연동. 진행률은 [Progress] 콜백으로 올린다 — 초 단위 작업이 무반응으로 보이지 않게 한다.
 * 인증 실패는 [UndineException.AuthenticationFailed] 로 번역하고 원격 URL 토큰을 남기지 않는다.
 */
interface RemoteGateway {

    suspend fun clone(url: String, into: RepositoryPath, onProgress: (Progress) -> Unit)

    suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef>

    suspend fun pull(remote: String, onProgress: (Progress) -> Unit)

    suspend fun push(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushResult
}
