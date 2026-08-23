package dev.undine.application.merge

import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.merge.SkipConfirmation

/**
 * 지금 적용 중인 커밋을 건너뛴다. **그 커밋의 변경은 결과 이력에서 사라지고 되돌릴 수 없다.**
 *
 * 그래서 [SkipConfirmation] 없이는 실행할 수 없다 — 화면(UND-24)은 사라질 커밋과 복구 불가성을
 * 보여 준 뒤에만 확인을 만들어 넘긴다. 확인한 커밋과 지금 멈춰 있는 커밋이 다르면
 * [MergeService] 가 건너뛰지 않는다(낡은 확인).
 *
 * 리베이스 전용이라 병합 진행 중에 호출하면 상태 위반 예외가 올라온다.
 */
class SkipRebaseCommitUseCase(private val mergeService: MergeService) {

    suspend fun execute(confirmation: SkipConfirmation): RebaseResult =
        mergeService.skipRebaseCommit(confirmation)
}
