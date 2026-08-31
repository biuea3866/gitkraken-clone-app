package dev.undine.application.staging

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.AmendConfirmation
import dev.undine.domain.CommitId
import dev.undine.domain.StagingGateway
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * amend 의 조회 → 확인 → 실행 순서를 엮고 되돌리기를 기록한다.
 *
 * presentation 은 [StagingGateway] 를 직접 부르지 않고 이 UseCase 에 **사용자 의사만** 전달한다 —
 * [request] 는 "고치고 싶다", [confirm] 은 "원격에 있는 걸 알지만 고치겠다" 다.
 * 확인 값 자체를 만드는 것은 여기서 하고, 그 값이 유효한지 판단하는 것은 Gateway 가 실행 직전에 한다.
 *
 * amend 와 기록은 한 [NonCancellable] 단위다 (결정 A-L2) — HEAD 를 다시 쓴 뒤 취소로 기록만 빠지면
 * 원본으로 돌아갈 단서가 이력에서 사라진다. 조회(preflight)는 저장소를 바꾸지 않으므로 그 구간
 * **밖**에 둔다: 확인이 필요해 실행하지 않은 경로까지 취소를 막을 이유가 없다.
 */
class AmendCommitUseCase(
    private val stagingGateway: StagingGateway,
    private val operationRecorder: OperationRecorder,
) {

    /**
     * amend 를 시도한다. 대상이 원격에 있으면 실행하지 않고 [AmendOutcome.ConfirmationRequired] 를 준다.
     */
    suspend fun request(message: String): AmendOutcome {
        val preflight = stagingGateway.inspectAmend()
        if (preflight.existsOnRemote) return AmendOutcome.ConfirmationRequired(preflight.target)
        return AmendOutcome.Amended(amend(message, AmendConfirmation.NotRequired))
    }

    /**
     * 사용자가 [target] 을 고쳐 쓰겠다고 확인했다.
     * [target] 은 [AmendOutcome.ConfirmationRequired.target] 그대로 넘긴다.
     */
    suspend fun confirm(message: String, target: CommitId): CommitOutcome =
        amend(message, AmendConfirmation.ConfirmedRemoteTarget(target))

    private suspend fun amend(message: String, confirmation: AmendConfirmation): CommitOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 amend 와 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = stagingGateway.amend(message, confirmation)
                CommitOutcome(result, operationRecorder.recordCommit(result))
            }
        }
    }
}
