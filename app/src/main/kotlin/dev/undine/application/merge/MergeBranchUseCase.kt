package dev.undine.application.merge

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RefName
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 대상 브랜치를 현재 브랜치로 병합하고 되돌리기를 기록한다.
 *
 * UseCase 는 얇다 — 판단은 [MergeService] 에 있고 여기서는 순서만 엮는다. 결과를 바꾸거나 삼키지 않고
 * 그대로 올린다: 충돌은 [MergeResult.Conflicted] 로, 시작 전 실패는 예외로 presentation 까지 간다.
 *
 * 병합과 기록은 한 [NonCancellable] 단위다 (결정 A-L2) — 병합이 끝난 뒤 취소로 기록만 빠지면
 * 저장소는 바뀌었는데 되돌릴 방법이 없다. 묶기 **전에** 호출자의 취소를 확인한다.
 *
 * 화면 배선은 아직 없다(UND-51 범위 밖). 그래도 기록을 여기에 두는 이유는, 배선되는 시점에
 * 기록 경로를 다시 찾지 않게 하기 위해서다 — 그래프 조작 경로와 이 경로가 같은 규칙을 쓴다.
 */
class MergeBranchUseCase(
    private val mergeService: MergeService,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(target: RefName, allowFastForward: Boolean = true): MergeOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 병합과 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = mergeService.merge(target, allowFastForward)
                MergeOutcome(result, operationRecorder.recordMerge(result, target.value))
            }
        }
    }
}
