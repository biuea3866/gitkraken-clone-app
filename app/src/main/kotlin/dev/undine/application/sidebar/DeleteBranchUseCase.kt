package dev.undine.application.sidebar

import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName

/**
 * 브랜치를 삭제한다.
 *
 * [force] 판단은 하지 않고 결과를 그대로 돌려준다 — 미병합 브랜치의 강제 삭제 여부는
 * 사용자 확인을 받은 화면이 정한다. 삭제는 reflog(UND-30)로만 복구되므로
 * 이 UseCase 가 스스로 강제 삭제로 승격하지 않는 것이 중요하다.
 */
class DeleteBranchUseCase(private val refGateway: RefGateway) {

    suspend operator fun invoke(name: RefName, force: Boolean): DeleteBranchResult =
        refGateway.deleteBranch(name, force)
}
