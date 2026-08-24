package dev.undine.application.rebase

import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.domain.rebase.RebaseTarget

/**
 * 리베이스 대상 커밋을 읽는다. 계획의 출발점이며, **저장소를 바꾸지 않는다**.
 *
 * @throws UndineException.NotFound upstream 을 찾을 수 없을 때
 */
class LoadRebaseTargetsUseCase(private val rebaseGateway: InteractiveRebaseGateway) {

    suspend fun execute(upstream: RefName): List<RebaseTarget> = rebaseGateway.listTargets(upstream)
}

/**
 * 계획을 적용한다. **여기서 처음 저장소가 바뀐다** — 그 전까지 화면은 계획만 편집한다.
 *
 * 충돌·멈춤은 실패가 아니라 [InteractiveRebaseOutcome] 으로 돌아온다. 화면은 그 값으로 다음 할 일
 * (충돌 해결 / 편집 후 계속)을 안내한다.
 *
 * @throws UndineException.StateViolation 계획이 규칙을 어겼거나 이미 진행 중일 때
 */
class ApplyRebasePlanUseCase(private val rebaseGateway: InteractiveRebaseGateway) {

    suspend fun execute(upstream: RefName, plan: RebasePlan): InteractiveRebaseOutcome =
        rebaseGateway.apply(upstream, plan)
}

/** 진행 중인 리베이스가 몇 번째 커밋을 적용 중인지. 진행 중이 아니면 null 이다. */
class LoadRebaseProgressUseCase(private val rebaseGateway: InteractiveRebaseGateway) {

    suspend fun execute(): RebaseRunProgress? = rebaseGateway.progress()
}
