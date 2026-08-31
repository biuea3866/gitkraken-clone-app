package dev.undine.application.rebase

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.domain.rebase.RebaseTarget
import dev.undine.domain.undo.GitOperationKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 리베이스 대상 커밋을 읽는다. 계획의 출발점이며, **저장소를 바꾸지 않는다**.
 *
 * @throws UndineException.NotFound upstream 을 찾을 수 없을 때
 */
class LoadRebaseTargetsUseCase(private val rebaseGateway: InteractiveRebaseGateway) {

    suspend fun execute(upstream: RefName): List<RebaseTarget> = rebaseGateway.listTargets(upstream)
}

/**
 * 계획 적용 결과. domain 결과를 그대로 담고 되돌리기 이력의 사정만 덧붙인다 (결정 G30 1).
 *
 * @property undoRecordFailure null 이 아니면 **적용은 끝났고 Undo 항목만 남지 않았다.**
 */
data class RebasePlanOutcome(
    val outcome: InteractiveRebaseOutcome,
    val undoRecordFailure: UndineException?,
)

/**
 * 계획을 적용하고 되돌리기를 기록한다. **여기서 처음 저장소가 바뀐다** — 그 전까지 화면은 계획만
 * 편집한다.
 *
 * 충돌·멈춤은 실패가 아니라 [InteractiveRebaseOutcome] 으로 돌아온다. 화면은 그 값으로 다음 할 일
 * (충돌 해결 / 편집 후 계속)을 안내하며, 그 두 상태는 저장소가 진행 중이라 남길 되돌리기가 없다.
 *
 * 적용과 기록은 한 [NonCancellable] 단위다 (결정 A-L2) — 커밋을 새로 쓴 뒤 취소로 기록만 빠지면
 * 원래 커밋으로 돌아갈 단서가 이력에서 사라진다.
 *
 * @throws UndineException.StateViolation 계획이 규칙을 어겼거나 이미 진행 중일 때
 */
class ApplyRebasePlanUseCase(
    private val rebaseGateway: InteractiveRebaseGateway,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(upstream: RefName, plan: RebasePlan): RebasePlanOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 적용과 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val outcome = rebaseGateway.apply(upstream, plan)
                RebasePlanOutcome(outcome, operationRecorder.recordApplied(outcome, upstream))
            }
        }
    }
}

/** 완료된 대화형 리베이스를 `REBASE` 기록으로 남긴다. 멈춤·변경 없음은 되돌릴 것이 없다. */
private suspend fun OperationRecorder.recordApplied(
    outcome: InteractiveRebaseOutcome,
    upstream: RefName,
): UndineException? = when (outcome) {
    is InteractiveRebaseOutcome.Completed -> recordHardReset(
        operation = GitOperationKind.REBASE,
        previousHead = outcome.previousHead,
        baseline = outcome.baseline,
        targetLabel = upstream.value,
    )

    is InteractiveRebaseOutcome.Conflicted,
    is InteractiveRebaseOutcome.StoppedForEdit,
    InteractiveRebaseOutcome.NothingToDo,
    -> null
}

/** 진행 중인 리베이스가 몇 번째 커밋을 적용 중인지. 진행 중이 아니면 null 이다. */
class LoadRebaseProgressUseCase(private val rebaseGateway: InteractiveRebaseGateway) {

    suspend fun execute(): RebaseRunProgress? = rebaseGateway.progress()
}
