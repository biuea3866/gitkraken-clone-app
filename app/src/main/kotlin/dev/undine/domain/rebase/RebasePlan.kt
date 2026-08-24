package dev.undine.domain.rebase

import dev.undine.domain.Commit

/**
 * 리베이스 대상 커밋 1건과 그 커밋이 **이미 원격에 있는지**.
 *
 * 원격에 있는 커밋을 다시 쓰면 이력이 갈라진다 — 계획 화면이 경고할 근거라서 대상에 함께 담는다.
 */
data class RebaseTarget(val commit: Commit, val isPushed: Boolean)

/** 계획의 한 줄 — 어떤 커밋을 어떻게 할지. */
data class RebasePlanStep(val target: RebaseTarget, val action: RebaseAction) {

    val commit: Commit get() = target.commit
}

/** 계획이 지금 실행될 수 없는 이유. 화면은 이 목록을 그대로 사유로 보여준다. */
sealed interface RebasePlanViolation {

    /** 첫 줄이 squash·fixup 이다 — 합칠 앞 커밋이 없다. */
    data object FirstStepCannotAbsorb : RebasePlanViolation

    /** 전부 drop 이다 — 결과가 빈 리베이스다. */
    data object EverythingDropped : RebasePlanViolation
}

/** 계획을 적용하면 이력이 어떻게 되는지. squash·fixup 으로 묶인 커밋을 한 항목으로 보여준다. */
sealed interface RebasePreviewEntry {

    /**
     * 결과에 남는 커밋. [absorbed] 는 이 커밋에 합쳐지는 커밋들이다(원래 순서).
     *
     * 첫 줄이 squash·fixup 이면 합칠 앞 커밋이 없어 자기 자신이 [Kept] 로 온다 — 그 상태는
     * [RebasePlanViolation.FirstStepCannotAbsorb] 로 이미 막히므로 미리보기는 추측하지 않는다.
     */
    data class Kept(val step: RebasePlanStep, val absorbed: List<RebasePlanStep>) : RebasePreviewEntry

    /** 결과에서 빠지는 커밋. */
    data class Dropped(val step: RebasePlanStep) : RebasePreviewEntry
}

/**
 * 대화형 리베이스 계획. **불변 값**이라 편집은 새 계획을 만든다.
 *
 * 순서는 `git rebase -i` 와 같은 **오래된 것부터**다 — squash·fixup 이 "앞 커밋" 에 합쳐지는
 * 방향이 그 순서에 달려 있어, 화면이 최신순으로 보여주더라도 계획 자체는 이 순서를 유지한다.
 *
 * 이 타입은 저장소를 알지 못한다. 적용 전까지 저장소를 건드리지 않는다는 요구가 여기서 구조로
 * 지켜진다 — 계획을 아무리 편집해도 Gateway 호출이 생기지 않는다.
 */
class RebasePlan private constructor(val steps: List<RebasePlanStep>) {

    /** 원격에 이미 있는 커밋을 다시 쓰는지 — 이력 분기 경고의 근거다. */
    val rewritesPushedCommits: Boolean
        get() = steps.any { it.target.isPushed && it.action !is RebaseAction.Pick }

    /** 실행 중 멈추는 줄이 있는지. */
    val stopsDuringRun: Boolean get() = steps.any { it.action.stopsDuringRun }

    /** 지금 실행할 수 있는지 — 위반이 하나도 없을 때만. */
    val isApplicable: Boolean get() = violations().isEmpty()

    /** 지금 실행을 막는 이유들. 비어 있으면 실행할 수 있다. */
    fun violations(): List<RebasePlanViolation> = buildList {
        if (steps.firstOrNull()?.action?.absorbsIntoPrevious == true) {
            add(RebasePlanViolation.FirstStepCannotAbsorb)
        }
        if (steps.isNotEmpty() && steps.all { it.action is RebaseAction.Drop }) {
            add(RebasePlanViolation.EverythingDropped)
        }
    }

    /** [from] 번째 줄을 [to] 자리로 옮긴 새 계획. 범위를 벗어난 요청은 무시한다. */
    fun move(from: Int, to: Int): RebasePlan {
        if (from !in steps.indices || to !in steps.indices || from == to) return this
        val reordered = steps.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return RebasePlan(reordered)
    }

    /** [index] 번째 줄의 동작을 바꾼 새 계획. 범위를 벗어난 요청은 무시한다. */
    fun withAction(index: Int, action: RebaseAction): RebasePlan {
        if (index !in steps.indices) return this
        return RebasePlan(steps.mapIndexed { at, step -> if (at == index) step.copy(action = action) else step })
    }

    /**
     * 계획의 결과 모양. squash·fixup 은 **앞의 남는 커밋**에 흡수되고, drop 은 따로 표시된다.
     *
     * 흡수 대상은 drop 을 건너뛴 직전의 남는 커밋이다 — drop 된 커밋에 합치면 결과에 없는 커밋에
     * 합치는 셈이 된다.
     */
    fun preview(): List<RebasePreviewEntry> {
        val entries = mutableListOf<RebasePreviewEntry>()
        steps.forEach { step ->
            val lastKept = entries.lastOrNull { it is RebasePreviewEntry.Kept } as? RebasePreviewEntry.Kept
            when {
                step.action is RebaseAction.Drop -> entries += RebasePreviewEntry.Dropped(step)
                step.action.absorbsIntoPrevious && lastKept != null ->
                    entries[entries.indexOf(lastKept)] = lastKept.copy(absorbed = lastKept.absorbed + step)

                else -> entries += RebasePreviewEntry.Kept(step, absorbed = emptyList())
            }
        }
        return entries
    }

    companion object {

        /** 대상 전부를 `pick` 으로 여는 계획. 사용자가 아무것도 고치지 않으면 이력이 그대로다. */
        fun of(targets: List<RebaseTarget>): RebasePlan =
            RebasePlan(targets.map { target -> RebasePlanStep(target, RebaseAction.Pick) })
    }
}
