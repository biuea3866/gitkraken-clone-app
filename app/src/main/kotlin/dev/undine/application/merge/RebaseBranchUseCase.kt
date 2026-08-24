package dev.undine.application.merge

import dev.undine.domain.RefName
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult

/**
 * 현재 브랜치를 대상 위로 재배치한다.
 *
 * **되돌릴 수 없다** — 리베이스는 커밋을 새로 쓴다. 중단하려면 [AbortMergeOrRebaseUseCase] 를
 * 진행 중에 호출해야 하고, 끝난 뒤에는 원래 커밋으로 돌아갈 수 없다.
 */
class RebaseBranchUseCase(private val mergeService: MergeService) {

    suspend fun execute(target: RefName): RebaseResult = mergeService.rebase(target)
}
