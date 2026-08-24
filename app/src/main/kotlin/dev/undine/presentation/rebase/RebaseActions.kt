package dev.undine.presentation.rebase

import dev.undine.application.rebase.ApplyRebasePlanUseCase
import dev.undine.application.rebase.LoadRebaseProgressUseCase
import dev.undine.application.rebase.LoadRebaseTargetsUseCase

/**
 * 계획 화면이 쓰는 UseCase 묶음 (`ConflictActions`·`StagingActions` 와 같은 방식).
 *
 * 계획 **편집**에 쓰는 것은 하나도 없다 — 편집은 [dev.undine.domain.rebase.RebasePlan] 안에서
 * 끝나고, 이 묶음은 읽기(대상·진행률)와 적용에만 쓰인다.
 */
class RebaseActions(
    val loadTargets: LoadRebaseTargetsUseCase,
    val applyPlan: ApplyRebasePlanUseCase,
    val loadProgress: LoadRebaseProgressUseCase,
)
