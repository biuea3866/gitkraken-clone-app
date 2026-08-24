package dev.undine.application.merge

import dev.undine.domain.RefName
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService

/**
 * 대상 브랜치를 현재 브랜치로 병합한다.
 *
 * UseCase 는 얇다 — 판단은 [MergeService] 에 있고 여기서는 순서만 엮는다. 결과를 바꾸거나 삼키지 않고
 * 그대로 올린다: 충돌은 [MergeResult.Conflicted] 로, 시작 전 실패는 예외로 presentation 까지 간다.
 *
 * 배선(어느 구현을 주입할지)은 UND-26 소유다.
 */
class MergeBranchUseCase(private val mergeService: MergeService) {

    suspend fun execute(target: RefName, allowFastForward: Boolean = true): MergeResult =
        mergeService.merge(target, allowFastForward)
}
