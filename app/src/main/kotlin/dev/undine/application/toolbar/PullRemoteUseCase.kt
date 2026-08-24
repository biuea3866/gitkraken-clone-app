package dev.undine.application.toolbar

import dev.undine.domain.Progress
import dev.undine.domain.RemoteGateway

/**
 * 원격 변경을 가져와 현재 브랜치에 병합한다.
 *
 * [FetchRemoteUseCase] 와 같은 이유로 얇다. 병합 충돌·더티 워킹트리는 Gateway 가 도메인 예외로
 * 올리므로 여기서 잡지 않는다 — 사용자 안내는 presentation 이 한다.
 */
class PullRemoteUseCase(private val remoteGateway: RemoteGateway) {

    suspend fun execute(remote: String, onProgress: (Progress) -> Unit) {
        remoteGateway.pull(remote, onProgress)
    }
}
