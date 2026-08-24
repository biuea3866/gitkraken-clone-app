package dev.undine.application.toolbar

import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway

/**
 * 참조 하나를 원격에 올린다.
 *
 * `force = true` 는 원격 이력을 덮어써 되돌릴 수 없다. **사용자 확인은 툴바가 받고**
 * (`RemoteToolbar` 의 force push 확인), 백업 ref 와 force-with-lease 는 `RemoteGatewayImpl` 이
 * 이미 책임진다 — 이 층은 확인된 의도를 그대로 옮기기만 한다.
 *
 * 거절([PushResult.Rejected])은 실패가 아니라 결과다. 예외로 바꾸지 않는다.
 */
class PushRemoteUseCase(private val remoteGateway: RemoteGateway) {

    suspend fun execute(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushResult =
        remoteGateway.push(ref, force, onProgress)
}
