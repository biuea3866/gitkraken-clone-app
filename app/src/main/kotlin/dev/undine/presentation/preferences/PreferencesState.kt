package dev.undine.presentation.preferences

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.LoadSigningPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.domain.UndineException
import dev.undine.domain.signing.SigningSettings
import dev.undine.domain.withDefault
import dev.undine.domain.withDefaultPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 환경설정 화면의 상태 홀더 (compose-ui 규칙 1). UseCase 만 호출하고 Gateway 를 알지 못한다.
 *
 * **저장 버튼이 없다.** 값이 바뀌면 [apply] 가 곧바로 화면에 반영하고 저장까지 끝낸다. 저장은
 * 전체 스냅샷 덮어쓰기가 아니라 `SettingsGateway.update(transform)` 부분 갱신이라, 화면이 읽어 둔
 * 값과 저장된 값 사이에 다른 갱신(창 크기·탭)이 끼어들어도 그 갱신을 지우지 않는다.
 *
 * **저장과 읽기는 시작한 순서대로 실행한다.** 요청마다 코루틴을 따로 띄우면 먼저 누른 변경이
 * 나중에 `update` 에 진입할 수 있고, 그러면 화면은 최신 값을 파일은 이전 값을 갖는다. 순번 검사는
 * 화면 반영만 막을 뿐 이 어긋남을 되돌리지 못한다. 그래서 이 홀더의 설정 작업은 [enqueue] 한
 * 줄로 이어 붙인다 — 자원 잠금이 아니라 자기가 시작한 작업의 실행 순서라 홀더가 소유한다
 * (읽기–수정–쓰기의 원자성은 그대로 Gateway 몫이다).
 *
 * **실패하면 화면을 이전 값으로 되돌린다.** 저장하지 못했는데 새 값이 남아 있으면 사용자는
 * 반영된 줄 안다. 설정을 읽지 못한 경우에는 기본값 상태로 열고 그 사실을 [loadFailure] 로 알린다 —
 * 설정 파일 하나 때문에 화면이 열리지 않는 편이 더 나쁘다.
 *
 * **설정 변경은 Git Undo 스택에 기록하지 않는다.** Git 되돌리기와 설정 되돌리기는 다른 개념이라
 * 한 스택에 섞으면 Undo 를 눌렀을 때 무엇이 되돌아갈지 예측할 수 없다. 되돌리기 경로는 이 화면이
 * 제공한다 — 항목별 [restoreDefault] 와 확인을 받는 전체 초기화([requestResetAll]·[confirmResetAll]).
 *
 * @param loadSigningPreferences 열린 저장소가 없으면 `null` 이다 — 서명 실효값을 읽을 곳이 없다.
 */
@Stable
class PreferencesState(
    private val scope: CoroutineScope,
    private val loadPreferences: LoadPreferencesUseCase,
    private val updatePreferences: UpdatePreferencesUseCase,
    private val loadSigningPreferences: LoadSigningPreferencesUseCase? = null,
) {
    /** 지금 보고 있는 탭. 화면은 이 탭의 내용만 그린다. */
    var selectedTab: PreferencesTab by mutableStateOf(PreferencesTab.GENERAL)
        private set

    /** 화면이 표시하는 설정. 읽기 전에도 기본값이라 화면이 빈 상태로 열리지 않는다. */
    var settings: Settings by mutableStateOf(Settings.DEFAULTS)
        private set

    /** 커밋 서명의 git 설정 실효값. 읽을 수 없으면 `null` 이며 서명 행을 만들지 않는다. */
    var signing: SigningSettings? by mutableStateOf(null)
        private set

    /** 설정을 읽지 못한 사유. `null` 이면 저장된 값을 그대로 읽었다. */
    var loadFailure: IOException? by mutableStateOf(null)
        private set

    /**
     * 서명 실효값을 읽지 못한 사유. 값을 모르는 것과 "서명이 꺼져 있다" 는 다르므로
     * 행을 조용히 비우지 않고 이 사유를 화면에 내보낸다.
     */
    var signingFailure: UndineException? by mutableStateOf(null)
        private set

    /** 마지막 즉시 저장이 실패한 사유. 다음 저장이 성공하면 지워진다. */
    var saveFailure: IOException? by mutableStateOf(null)
        private set

    /** 전체 초기화 확인을 보여주는 중인가. 확인 전에는 아무것도 되돌리지 않는다. */
    var isResetConfirmationVisible: Boolean by mutableStateOf(false)
        private set

    /**
     * 마지막으로 시작한 [settings] 갱신 요청의 순번. 화면이 읽지 않으므로 상태가 아니다.
     *
     * [apply] 의 저장과 [refresh] 의 읽기가 이 번호를 나눠 쓴다 — 둘 다 [settings] 를 쓰므로,
     * 결과 반영을 **마지막 요청에만** 허용해야 늦게 끝난 쪽이 최신 값을 낡은 값으로 덮지 않는다.
     *
     * 실행 순서는 [enqueue] 가 이미 맞춰 두지만 이 검사는 여전히 필요하다 — [apply] 의 낙관적
     * 반영은 줄에 서지 않고 즉시 일어나므로, 앞서 줄 서 있던 읽기가 뒤늦게 끝나며 그 값을 덮을 수 있다.
     */
    private var latestSettingsRequest: Int = 0

    /**
     * 마지막으로 시작한 서명 실효값 읽기의 순번. [apply] 는 서명을 건드리지 않으므로 번호가 따로다 —
     * 한 번호를 공유하면 저장이 겹친 [refresh] 가 읽어 온 서명 값까지 함께 버려진다.
     */
    private var latestSigningRequest: Int = 0

    /**
     * 이 홀더가 마지막으로 줄 세운 설정 작업. 다음 작업은 이것이 끝난 뒤에 시작한다.
     *
     * 시작 시점에 이미 끝난 자리표시자를 넣어 첫 작업도 같은 경로로 들어오게 한다 — 첫 요청만
     * 다르게 처리하면 그 분기가 곧 순서 구멍이 된다.
     */
    private var lastSettingsWork: Job = Job().apply { complete() }

    /** 저장된 설정과 서명 실효값을 다시 읽는다. 화면 진입 시 배선이 호출한다. */
    fun refresh() {
        val settingsRequest = ++latestSettingsRequest
        val signingRequest = ++latestSigningRequest
        enqueue { reload(settingsRequest, signingRequest) }
    }

    fun selectTab(tab: PreferencesTab) {
        selectedTab = tab
    }

    /** 지금 내용을 그릴 탭인가. 선택된 탭 하나만 그린다 — 화면의 디스패치가 이 판정을 쓴다. */
    fun rendersContent(tab: PreferencesTab): Boolean = tab == selectedTab

    /**
     * 설정 한 부분을 바꿔 곧바로 반영·저장한다. 탭이 조립한 모든 편집기가 이 경로로 들어온다.
     *
     * [change] 는 저장된 값 위에 다시 적용된다 — 화면이 들고 있던 값이 아니라 저장 시점의 값이
     * 기준이라야 다른 갱신을 지우지 않는다.
     *
     * **화면은 저장 결과로만 갱신한다.** 낙관적으로 먼저 그리면 실패했을 때 무엇으로 되돌릴지가
     * 문제가 된다 — 되돌릴 대상인 "직전 화면 값" 자체가 저장되지 않은 값일 수 있어, 연속 실패에서
     * 화면과 파일이 갈린다. 순서는 [enqueue] 의 작업 줄이 이미 보장하고 저장 대상은 로컬 파일이라,
     * 저장을 기다렸다 그리는 비용이 그 위험보다 싸다.
     */
    fun apply(change: (Settings) -> Settings) {
        // **화면 값으로 "변경 없음" 을 판단하지 않는다.** 화면이 파일보다 낡았으면 그 판단이 틀리고,
        // 저장을 건너뛴 채 파일의 다른 값이 그대로 남는다. 무변경 여부는 저장 경로가 저장된 값
        // 위에서 판단한다 — 같은 값을 다시 쓰는 비용이 갈림 위험보다 싸다.
        val request = ++latestSettingsRequest
        enqueue {
            try {
                // 성공한 저장은 **세대와 무관하게** 반영한다 — 작업 줄이 FIFO 라 이 값이 곧 지금
                // 파일에 있는 값이다. 뒤 요청이 실패했다고 앞의 성공을 버리면 화면만 옛 값이 된다.
                settings = updatePreferences.execute(change)
                if (request == latestSettingsRequest) saveFailure = null
            } catch (failure: IOException) {
                if (request == latestSettingsRequest) saveFailure = failure
            }
        }
    }

    /** 항목 하나만 기본값으로 되돌린다. 다른 항목은 그대로 둔다. */
    fun restoreDefault(preference: SettingsPreference) {
        apply { it.withDefault(preference) }
    }

    /** 전체 초기화 확인을 띄운다. 무엇이 되돌아가는지 알린 뒤에만 수행한다. */
    fun requestResetAll() {
        isResetConfirmationVisible = true
    }

    fun cancelResetAll() {
        isResetConfirmationVisible = false
    }

    /**
     * 확인을 받은 전체 초기화. 화면·동작 취향과 탭 세션만 되돌리고 신원 프로필·외부 도구·
     * 저장소 git 설정은 건드리지 않는다. 되돌리기 경로는 항목별 복원과 같은 즉시 적용 경로다.
     */
    fun confirmResetAll() {
        isResetConfirmationVisible = false
        apply { it.withDefaultPreferences() }
    }

    /**
     * 설정 작업을 앞선 작업 뒤에 줄 세운다. 호출한 순서가 곧 `SettingsGateway` 진입 순서다.
     *
     * [Job.join] 은 앞선 작업이 실패·취소로 끝나도 예외를 올리지 않는다 — 한 요청의 실패가
     * 뒤이은 요청을 막지 않아야 하고, 각 작업은 자기 실패를 자기가 처리한다.
     */
    private fun enqueue(work: suspend () -> Unit) {
        val previous = lastSettingsWork
        lastSettingsWork = scope.launch {
            previous.join()
            work()
        }
    }

    /**
     * 읽어 온 값은 **자기 요청이 아직 마지막일 때만** 화면에 쓴다. 읽기는 저장·다른 읽기와 겹칠 수
     * 있고 시작 순서대로 끝나지도 않으므로, 순번을 보지 않으면 늦게 끝난 읽기가 최신 값을 덮는다.
     */
    private suspend fun reload(settingsRequest: Int, signingRequest: Int) {
        var loadError: IOException? = null
        val loaded = try {
            loadPreferences.execute()
        } catch (failure: IOException) {
            loadError = failure
            Settings.DEFAULTS
        }
        // 값은 **세대와 무관하게** 반영한다 — 작업 줄이 FIFO 라 지금 읽은 것이 곧 지금 파일의 값이다.
        // 세대로 거르면 뒤이어 큐에 들어온 저장이 실패했을 때 화면이 읽은 값조차 못 받는다.
        settings = loaded
        if (settingsRequest == latestSettingsRequest) loadFailure = loadError

        var signingError: UndineException? = null
        val effective = try {
            loadSigningPreferences?.execute()
        } catch (failure: UndineException) {
            signingError = failure
            null
        }
        signing = effective
        if (signingRequest == latestSigningRequest) signingFailure = signingError
    }
}
