package dev.undine.presentation.rebase

import dev.undine.application.rebase.ApplyRebasePlanUseCase
import dev.undine.application.rebase.LoadRebaseProgressUseCase
import dev.undine.application.rebase.LoadRebaseTargetsUseCase
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.domain.rebase.RebaseTarget
import dev.undine.testsupport.commit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal val UPSTREAM = RefName("refs/heads/main")

/** 대상 목록을 주고 적용 호출을 기록하는 대역. 편집이 저장소에 닿는지 여기서 본다. */
internal class RecordingRebaseGateway(
    private val targets: List<RebaseTarget> = emptyList(),
    private val outcome: InteractiveRebaseOutcome = InteractiveRebaseOutcome.Completed,
    private val progress: RebaseRunProgress? = null,
    private val failOnApply: UndineException? = null,
) : InteractiveRebaseGateway {

    /** 적용된 계획들. **비어 있어야** 편집이 저장소를 건드리지 않았다는 뜻이다. */
    val appliedPlans = mutableListOf<RebasePlan>()

    var listCalls: Int = 0
        private set

    override suspend fun listTargets(upstream: RefName): List<RebaseTarget> {
        listCalls++
        return targets
    }

    override suspend fun apply(upstream: RefName, plan: RebasePlan): InteractiveRebaseOutcome {
        failOnApply?.let { throw it }
        appliedPlans += plan
        return outcome
    }

    override suspend fun progress(): RebaseRunProgress? = progress
}

internal fun rebaseStateWith(gateway: InteractiveRebaseGateway): RebasePlanState = RebasePlanState(
    actions = rebaseActionsOf(gateway),
    upstream = { UPSTREAM },
    scope = unconfinedScope(),
)

internal fun rebaseActionsOf(gateway: InteractiveRebaseGateway): RebaseActions = RebaseActions(
    loadTargets = LoadRebaseTargetsUseCase(gateway),
    applyPlan = ApplyRebasePlanUseCase(gateway),
    loadProgress = LoadRebaseProgressUseCase(gateway),
)

/** 상태 전이를 호출 즉시 관찰하려고 Unconfined 를 쓴다 — 화면 테스트는 waitForIdle 로 기다린다. */
internal fun unconfinedScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

/** 대상 읽기가 실패하는 대역. 실패가 빈 목록으로 뭉개지지 않는지 본다. */
internal class FailingListGateway : InteractiveRebaseGateway {

    override suspend fun listTargets(upstream: RefName): List<RebaseTarget> =
        throw UndineException.NotFound(UndineException.NotFound.Kind.REF, upstream.value)

    override suspend fun apply(upstream: RefName, plan: RebasePlan): InteractiveRebaseOutcome =
        error("실패한 목록으로는 적용까지 가지 않는다")

    override suspend fun progress(): RebaseRunProgress? = null
}

/** 메시지 하나당 커밋 하나. `pushed` 로 지정한 메시지는 원격에 있는 것으로 만든다. */
internal fun targetsOf(vararg messages: String, pushed: Set<String> = emptySet()): List<RebaseTarget> =
    messages.mapIndexed { index, message ->
        RebaseTarget(commit(index + 1, message = message), isPushed = message in pushed)
    }
