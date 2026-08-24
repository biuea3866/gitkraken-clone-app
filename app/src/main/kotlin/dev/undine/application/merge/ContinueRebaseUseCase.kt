package dev.undine.application.merge

import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult

/** 충돌을 해결한 뒤 남은 커밋을 이어서 적용한다. */
class ContinueRebaseUseCase(private val mergeService: MergeService) {

    suspend fun execute(): RebaseResult = mergeService.continueRebase()
}
