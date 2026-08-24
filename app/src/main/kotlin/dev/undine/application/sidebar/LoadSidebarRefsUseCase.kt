package dev.undine.application.sidebar

import dev.undine.domain.RefGateway
import dev.undine.domain.WorktreeOpsGateway

/**
 * 사이드바가 그릴 참조를 한 번에 모은다.
 *
 * Gateway 호출과 결과 조합만 한다 — 실패를 빈 목록으로 바꾸지 않고 그대로 올린다
 * (exception-handling 규칙 7). 디스패처는 각 GatewayImpl 이 정한다.
 */
class LoadSidebarRefsUseCase(
    private val refGateway: RefGateway,
    private val worktreeOpsGateway: WorktreeOpsGateway,
) {
    suspend operator fun invoke(): SidebarRefs = SidebarRefs(
        branches = refGateway.listBranches(),
        tags = refGateway.listTags(),
        stashes = worktreeOpsGateway.stashList(),
    )
}
