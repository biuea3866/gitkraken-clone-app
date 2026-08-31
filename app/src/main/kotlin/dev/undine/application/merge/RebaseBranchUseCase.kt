package dev.undine.application.merge

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RefName
import dev.undine.domain.merge.MergeService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 현재 브랜치를 대상 위로 재배치하고 되돌리기를 기록한다.
 *
 * 리베이스는 커밋을 **새로 씀**에도 되돌릴 수 있다 — 시작 전 지점(`ORIG_HEAD`)이 결과에 실려 오므로
 * 그 지점으로 hard reset 하면 원래 커밋으로 돌아간다. 진행 중에 빠져나오는 것은 여전히
 * [AbortMergeOrRebaseUseCase] 다.
 *
 * 재배치와 기록은 한 [NonCancellable] 단위다 (결정 A-L2). 화면 배선은 아직 없다 —
 * [MergeBranchUseCase] 와 같은 이유로 기록을 여기에 둔다.
 */
class RebaseBranchUseCase(
    private val mergeService: MergeService,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(target: RefName): RebaseOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 재배치와 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = mergeService.rebase(target)
                RebaseOutcome(result, operationRecorder.recordRebase(result, target.value))
            }
        }
    }
}
