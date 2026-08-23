package dev.undine.application.staging

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway

/**
 * amend 의 조회 → 확인 → 실행 순서를 엮는다.
 *
 * presentation 은 [StagingGateway] 를 직접 부르지 않고 이 UseCase 에 **사용자 의사만** 전달한다 —
 * [request] 는 "고치고 싶다", [confirm] 은 "원격에 있는 걸 알지만 고치겠다" 다.
 * 확인 값 자체를 만드는 것은 여기서 하고, 그 값이 유효한지 판단하는 것은 Gateway 가 실행 직전에 한다.
 */
class AmendCommitUseCase(
    private val stagingGateway: StagingGateway,
) {

    /**
     * amend 를 시도한다. 대상이 원격에 있으면 실행하지 않고 [AmendOutcome.ConfirmationRequired] 를 준다.
     */
    suspend fun request(message: String): AmendOutcome {
        val preflight = stagingGateway.inspectAmend()
        if (preflight.existsOnRemote) return AmendOutcome.ConfirmationRequired(preflight.target)
        return AmendOutcome.Amended(stagingGateway.amend(message, AmendConfirmation.NotRequired))
    }

    /**
     * 사용자가 [target] 을 고쳐 쓰겠다고 확인했다.
     * [target] 은 [AmendOutcome.ConfirmationRequired.target] 그대로 넘긴다.
     */
    suspend fun confirm(message: String, target: CommitId): CommitResult =
        stagingGateway.amend(message, AmendConfirmation.ConfirmedRemoteTarget(target))
}
