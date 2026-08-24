package dev.undine.presentation.rebase

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.rebase.RebasePlanViolation
import dev.undine.domain.rebase.RebasePreviewEntry
import dev.undine.domain.rebase.RebaseRunProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 대화형 리베이스 계획 상태 홀더.
 *
 * **편집은 저장소를 건드리지 않는다.** [move]·[setAction]·[reword] 는 불변 [RebasePlan] 을 새 값으로
 * 바꾸기만 하고 UseCase 를 부르지 않는다 — 사용자가 자유롭게 만지다 [discard] 로 되돌릴 수 있다.
 * 저장소가 바뀌는 지점은 [apply] 하나다.
 *
 * @param upstream 리베이스 기준. 배선이 주입한다 — 화면이 보는 기준과 어긋나면 다른 커밋이 대상이 된다.
 */
@Stable
@Suppress("TooManyFunctions") // 읽기·재정렬·동작 지정·메시지 편집·적용·폐기·진행률이 한 화면의 전이다.
class RebasePlanState(
    private val actions: RebaseActions,
    private val upstream: () -> RefName?,
    private val scope: CoroutineScope,
) {
    /** 편집 중인 계획. `null` 이면 아직 대상을 읽지 않았거나 계획을 폐기한 상태다. */
    var plan: RebasePlan? by mutableStateOf(null)
        private set

    /** 적용 결과. 충돌·멈춤도 실패가 아니라 여기로 온다. */
    var outcome: InteractiveRebaseOutcome? by mutableStateOf(null)
        private set

    /** 진행 중인 리베이스의 진행률. 진행 중이 아니면 null 이다. */
    var progress: RebaseRunProgress? by mutableStateOf(null)
        private set

    /** 적용을 진행 중인지 — 같은 버튼을 두 번 눌러 두 번 시작하지 않게 하는 기준이다. */
    var applying: Boolean by mutableStateOf(false)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /** 대상이 하나도 없는지 — 빈 상태 안내와 계획 목록을 구분하는 기준이다. */
    val isEmpty: Boolean get() = plan?.steps.isNullOrEmpty()

    val violations: List<RebasePlanViolation> get() = plan?.violations().orEmpty()

    /** 적용할 수 있는지 — 계획이 규칙을 지키고, 대상이 있고, 아직 적용 중이 아닐 때. */
    val canApply: Boolean
        get() = plan?.let { it.isApplicable && it.steps.isNotEmpty() } == true && !applying

    val preview: List<RebasePreviewEntry> get() = plan?.preview().orEmpty()

    /** 이미 원격에 올라간 커밋을 다시 쓰는지 — 이력 분기 경고의 근거다. */
    val rewritesPushedCommits: Boolean get() = plan?.rewritesPushedCommits == true

    /** 실행 중 멈추는 줄이 있는지 — 멈춘 화면을 오류로 오해하지 않게 미리 알린다. */
    val stopsDuringRun: Boolean get() = plan?.stopsDuringRun == true

    /** 대상 커밋을 읽어 전부 `pick` 인 계획을 만든다. 저장소는 바뀌지 않는다. */
    fun load() {
        val base = upstream() ?: return
        failure = null
        outcome = null
        scope.launch {
            plan = try {
                RebasePlan.of(actions.loadTargets.execute(base))
            } catch (thrown: UndineException) {
                failure = thrown
                null
            }
        }
    }

    fun move(from: Int, to: Int) {
        plan = plan?.move(from, to)
    }

    fun setAction(index: Int, action: RebaseAction) {
        plan = plan?.withAction(index, action)
    }

    /** `reword` 로 지정한 줄의 새 메시지를 고친다. 그 줄이 reword 가 아니면 무시한다. */
    fun reword(index: Int, message: String) {
        val current = plan ?: return
        if (current.steps.getOrNull(index)?.action !is RebaseAction.Reword) return
        plan = current.withAction(index, RebaseAction.Reword(message))
    }

    /**
     * 계획을 적용한다. **여기서 처음 저장소가 바뀐다.**
     *
     * 규칙을 어긴 계획은 부르지 않는다 — Gateway 도 같은 검사를 하지만, 누를 수 없는 버튼을 눌러
     * 예외로 알려주는 것은 안내가 아니라 사고다.
     */
    fun apply() {
        val current = plan
        val base = upstream()
        if (current == null || base == null || !canApply) return
        applying = true
        failure = null
        scope.launch {
            try {
                outcome = actions.applyPlan.execute(base, current)
            } catch (thrown: UndineException) {
                failure = thrown
            } finally {
                applying = false
            }
            refreshProgress()
        }
    }

    /** 계획을 폐기한다. 저장소는 그대로다 — 편집이 저장소에 닿은 적이 없다. */
    fun discard() {
        plan = null
        outcome = null
        failure = null
    }

    fun refreshProgress() {
        scope.launch {
            progress = try {
                actions.loadProgress.execute()
            } catch (thrown: UndineException) {
                failure = thrown
                null
            }
        }
    }
}
