package dev.undine.application.toolbar

import dev.undine.domain.Progress
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef

/**
 * 원격에서 참조를 가져온다.
 *
 * 툴바가 Gateway 를 직접 주입받지 않게 하는 얇은 층이다 (레이어 규칙 3) — 분기·검증 없이
 * 호출과 결과 전달만 한다 (wave 3 결정 A1). 디스패처 전환과 저장소 직렬화는
 * `RemoteGatewayImpl` 이 이미 책임지므로 여기서 다시 걸지 않는다.
 *
 * 갱신된 참조 수는 이 반환값의 크기로만 알 수 있다 — 계약에 별도 건수 필드가 없다.
 */
class FetchRemoteUseCase(private val remoteGateway: RemoteGateway) {

    suspend fun execute(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef> =
        remoteGateway.fetch(remote, onProgress)
}
