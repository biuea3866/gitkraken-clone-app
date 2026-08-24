package dev.undine.application.sidebar

import dev.undine.domain.RefGateway
import dev.undine.domain.RefName

/** 브랜치 이름을 바꾼다. 이름 형식 검증은 `RefGateway` 구현이 소유한다. */
class RenameBranchUseCase(private val refGateway: RefGateway) {

    suspend operator fun invoke(from: RefName, to: RefName) = refGateway.renameBranch(from, to)
}
