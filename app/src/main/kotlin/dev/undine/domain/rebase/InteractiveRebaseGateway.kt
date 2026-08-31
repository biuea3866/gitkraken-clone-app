package dev.undine.domain.rebase

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException

/**
 * 계획을 적용한 결과. **충돌과 멈춤은 실패가 아니다** — 저장소가 진행 중으로 남고 사용자가 이어간다.
 *
 * 이 타입을 `domain/merge` 의 `RebaseResult` 와 공유하지 않는 이유는 두 가지다. 하위 패키지끼리
 * 교차 참조가 금지돼 있고([[architecture-layers]]), 대화형은 `edit` 로 **충돌 없이도 멈춘다** —
 * 같은 shape 로 뭉치면 화면이 두 멈춤을 구분할 수 없다.
 */
sealed interface InteractiveRebaseOutcome {

    /**
     * 계획 전체가 적용됐다.
     *
     * [previousHead] 와 [baseline] 은 **적용과 같은 임계 구역에서** 캡처한 되돌리기 재료다 (UND-73) —
     * 기록하는 호출자가 적용 뒤 스스로 읽으면 그 사이의 다른 조작까지 반영된 상태가 남는다.
     * 되돌릴 기록을 남기는 변이가 이것뿐이라 여기에만 담는다.
     */
    data class Completed(
        val previousHead: CommitId?,
        val baseline: RepositoryBaseline,
    ) : InteractiveRebaseOutcome

    /** 적용할 것이 없었다 (대상이 없거나 이미 최신). */
    data object NothingToDo : InteractiveRebaseOutcome

    /** 충돌로 멈췄다. [paths] 를 해결하고 이어가야 한다. */
    data class Conflicted(val paths: List<String>) : InteractiveRebaseOutcome

    /** `edit` 지시로 멈췄다. [commit] 은 지금 적용 중인 커밋이며 읽을 수 없으면 null 이다. */
    data class StoppedForEdit(val commit: CommitId?) : InteractiveRebaseOutcome
}

/** 진행 중인 리베이스가 몇 번째 커밋을 적용 중인지. git 이 `msgnum/end` 로 남기는 값이다. */
data class RebaseRunProgress(val applied: Int, val total: Int)

/**
 * 대화형 리베이스의 **실행**을 맡는 외부 Git 접근 계약. 구현은 `InteractiveRebaseGatewayImpl` 이다.
 *
 * 계획의 규칙(첫 줄 squash 금지·전부 drop 거부)은 [RebasePlan] 이 갖고, 여기에는 "무엇을
 * 실행하는가" 만 둔다. 계획을 편집하는 동안 이 계약은 호출되지 않는다 — 적용 전까지 저장소를
 * 건드리지 않는다는 요구가 그렇게 지켜진다.
 */
interface InteractiveRebaseGateway {

    /**
     * [upstream] 이후의 커밋을 **오래된 것부터** 준다 (`git rebase -i` 의 todo 순서).
     *
     * 각 커밋이 원격 추적 참조에서 닿는지(`isPushed`)를 함께 판정한다 — 화면이 이력 분기 경고를
     * 띄울 근거이며, 그 판정을 화면에서 다시 하려면 원격 참조를 알아야 해 계층이 뒤집힌다.
     *
     * @throws UndineException.NotFound [upstream] 을 참조로도 커밋으로도 찾을 수 없을 때
     */
    suspend fun listTargets(upstream: RefName): List<RebaseTarget>

    /**
     * [plan] 을 [upstream] 위로 적용한다. 계획이 실행 불가([RebasePlan.isApplicable] 가 false)면
     * 시작하지 않는다 — 규칙 위반을 JGit 에게 떠넘기면 반쯤 진행된 상태가 남는다.
     *
     * @throws UndineException.StateViolation 계획이 실행 불가이거나 이미 진행 중일 때
     */
    suspend fun apply(upstream: RefName, plan: RebasePlan): InteractiveRebaseOutcome

    /** 진행 중인 리베이스의 진행률. 진행 중이 아니거나 읽을 수 없으면 null 이다. */
    suspend fun progress(): RebaseRunProgress?
}
