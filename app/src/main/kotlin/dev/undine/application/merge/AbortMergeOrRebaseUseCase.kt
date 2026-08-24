package dev.undine.application.merge

import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeService

/**
 * 진행 중인 병합 또는 리베이스를 시작 전 상태로 되돌린다. 무엇이 진행 중인지는 저장소에서 읽으므로
 * 화면이 둘을 구분해 호출할 필요가 없다 — 앱을 다시 열어 진행 중 상태를 만난 사용자도 이 하나로 빠져나온다.
 *
 * **워킹트리의 충돌 해결 편집은 사라지고 되돌릴 수 없다.** 그래서 [AbortConfirmation] 없이는 실행할 수
 * 없다 — 화면은 사라질 편집 목록과 복구 불가성을 보여 준 뒤에만 확인을 만들어 넘긴다.
 * 커밋은 `ORIG_HEAD` 로 시작 전 지점까지 복구된다.
 */
class AbortMergeOrRebaseUseCase(private val mergeService: MergeService) {

    suspend fun execute(confirmation: AbortConfirmation) = mergeService.abort(confirmation)
}
