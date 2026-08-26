package dev.undine.infrastructure.git.repository

import dev.undine.domain.RepositorySessionGateway
import dev.undine.domain.RepositorySessions

/**
 * [RepositorySessionGateway] 의 JGit 구현. 다중 저장소 탭이 세션 핸들을 얻는 유일한 경로다.
 *
 * 여기서는 락을 걸지 않는다 — 전이의 직렬화는 [GitAccess] 뒤의 [RepositoryHolder] 가 한 임계구역에서
 * 끝낸다. `RepositoryGatewayImpl` 과 **같은 [GitAccess] 인스턴스**를 받아야 다른 Gateway 들이 보는
 * 활성 핸들과 탭의 활성 세션이 어긋나지 않는다 (배선은 UND-51).
 *
 * 경로 정규화도 하지 않는다. 세션 식별자는 홀더가 정해 돌려주는 값이고, 이 클래스는 그 값을
 * 그대로 통과시킨다 — 경계마다 정규화하면 별칭 경로에서 세션이 갈린다 (결정 C2 정정 3).
 */
class RepositorySessionGatewayImpl(
    private val gitAccess: GitAccess,
) : RepositorySessionGateway {

    override suspend fun <T> transition(block: suspend (RepositorySessions) -> T): T =
        gitAccess.withSessions(block)
}
