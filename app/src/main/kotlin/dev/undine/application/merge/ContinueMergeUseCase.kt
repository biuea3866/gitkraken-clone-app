package dev.undine.application.merge

import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService

/** 충돌을 해결한 뒤 병합을 이어서 끝낸다. 미해결 파일이 남아 있으면 [MergeResult.Conflicted] 가 그대로 올라온다. */
class ContinueMergeUseCase(private val mergeService: MergeService) {

    suspend fun execute(): MergeResult = mergeService.continueMerge()
}
