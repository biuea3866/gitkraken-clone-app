package dev.undine.presentation.patch

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 갈래가 겹친 시작을 어떻게 다루는가.
 *
 * 어느 쪽인지는 **결과를 버려도 되는가**로 갈린다. 조회·검사는 다시 하면 그만이라 마지막 것만 남기면
 * 되지만, 저장·적용은 이미 디스크와 저장소를 바꾼 뒤라 결과를 버리면 사용자가 무슨 일이 일어났는지
 * 알 길이 없다.
 */
enum class PatchJobMode {
    /**
     * 마지막에 시작한 작업만 결과를 쓴다. 앞서 시작한 작업은 계속 돌되 그 결과는 버려진다 —
     * 늦게 끝난 앞선 조회가 사용자가 이미 바꾼 선택의 결과인 척 화면에 앉지 않게 한다.
     */
    LATEST_WINS,

    /**
     * 한 번에 하나만 돈다. 도는 동안 새 시작을 막고, 끝난 결과는 **반드시** 화면에 앉힌다.
     * 겹쳐 돌면 한쪽의 되돌리기가 다른 쪽이 만든 파일을 지우거나, 저장소에 두 패치가 잇달아 얹힌다.
     */
    EXCLUSIVE,
}

/**
 * 비동기 작업 한 갈래(lane)의 수명 장치.
 *
 * **세대 · 진행 여부 · 진행 상태를 한 자리에 묶는다.** 셋이 흩어져 있으면 지점마다 플래그를 하나씩
 * 더하게 되고, 그때마다 같은 계열의 결함이 새 지점에서 다시 난다 — 늦게 끝난 결과가 바뀐 선택을
 * 덮거나(세대), 같은 작업이 겹쳐 돌거나(수명), 도는 중에 상태만 초기화돼 진행이 숨는다(상태).
 *
 * 모든 비동기 경로는 [PatchState] 의 단일 진입점을 거쳐 이 장치를 쓴다. [kotlinx.coroutines.launch]
 * 를 직접 부르면 셋이 다시 흩어진다.
 */
@Stable
class PatchJobLane<S : Any>(private val idle: S, private val mode: PatchJobMode) {

    /** 갈래의 진행 상태. 화면이 그리는 값은 이것 하나다 — 수명과 상태가 따로 놀지 않는다. */
    var state: S by mutableStateOf(idle)
        private set

    /** 작업이 도는 중인가. 재진입 차단과 진행 표시가 **같은 값**을 본다. */
    var isRunning: Boolean by mutableStateOf(false)
        private set

    private var generation: Int = 0

    /**
     * 갈래를 처음 상태로 되돌린다. 선택·대상이 바뀌는 지점이 부른다.
     *
     * [PatchJobMode.EXCLUSIVE] 갈래는 **도는 중이면 아무것도 하지 않는다** — 진행 상태를 지우면
     * 사용자에게는 끝난 것처럼 보이는데 실제로는 계속 돌고, 그 사이 두 번째 작업까지 열린다.
     */
    fun reset() {
        when (mode) {
            PatchJobMode.LATEST_WINS -> {
                generation++
                isRunning = false
                state = idle
            }

            PatchJobMode.EXCLUSIVE -> if (!isRunning) state = idle
        }
    }

    /** 작업 하나를 시작하고 그 세대를 준다. 시작하지 못하면 `null` — 이미 도는 중인 [PatchJobMode.EXCLUSIVE] 갈래다. */
    internal fun begin(running: S): Int? {
        if (mode == PatchJobMode.EXCLUSIVE && isRunning) return null
        if (mode == PatchJobMode.LATEST_WINS) generation++
        isRunning = true
        state = running
        return generation
    }

    /** 결과를 앉힌다. 자기 세대가 이미 지났으면 버리고 `false` 를 준다. */
    internal fun complete(token: Int, result: S): Boolean {
        if (!isCurrent(token)) return false
        state = result
        return true
    }

    /** 작업이 끝났다. 그 사이 새 작업이 시작됐으면 진행 표시를 내리지 않는다 — 내리는 것은 새 작업의 몫이다. */
    internal fun finish(token: Int) {
        if (isCurrent(token)) isRunning = false
    }

    private fun isCurrent(token: Int): Boolean = token == generation
}
