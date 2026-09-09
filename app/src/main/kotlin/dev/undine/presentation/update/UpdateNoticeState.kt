package dev.undine.presentation.update

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.update.UpdateUseCases
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateInstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 한 시간의 밀리초. 주기 설정은 시간 단위다 ([UpdateCheckSettings.intervalHours]). */
private const val MILLIS_PER_HOUR: Long = 60L * 60L * 1000L

/**
 * 자동 업데이트 안내의 상태 홀더 (compose-ui 규칙 1). [UpdateUseCases] 만 부르고 Gateway 를 알지 못한다.
 *
 * **확인 실패를 화면에 띄우지 않지만 상태로는 구분한다** (결정 D6). 오프라인 사용자에게 매번 오류를
 * 띄우는 것은 소음이지만, 실패를 "최신입니다" 로 접으면 몇 달째 확인이 실패하는 앱이 그 사실을
 * 영영 말하지 않는다 — [lastCheckResult] 가 그 구분을 들고 있다.
 *
 * **동의 없이 아무것도 열지 않는다.** 확인만 자동이고, [install] 은 사용자가 누른 뒤에만 불린다.
 * 그 순간에도 [installBlockedReason] 이 사유를 주면 UseCase 를 부르지 않고 미룬다 (결정 D11) —
 * 화면 이동·저장소 전환과 **같은 판정**을 보므로 세 번째 판정이 생기지 않는다.
 *
 * @param installBlockedReason 진행 중인 작업 때문에 설치를 미뤄야 하는 사유. 미룰 것이 없으면 `null`.
 * @param awaitNextCheck 다음 확인까지의 대기. 테스트가 시간을 제어할 수 있게 주입 지점으로 둔다 —
 *   기본값은 실제 [delay] 다.
 */
@Stable
class UpdateNoticeState(
    private val scope: CoroutineScope,
    private val updates: UpdateUseCases,
    private val installBlockedReason: () -> String?,
    private val awaitNextCheck: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    /** 안내할 새 버전. `null` 이면 배너를 그리지 않는다. */
    var availableRelease: AvailableRelease? by mutableStateOf(null)
        private set

    /**
     * 마지막 확인 결과. 화면은 [availableRelease] 만 보고 그리지만, 확인 실패와 "업데이트 없음" 은
     * 이 값으로 갈린다 — 둘을 같은 값으로 만들지 않기 위한 자리다 (결정 D6).
     */
    var lastCheckResult: UpdateCheckResult? by mutableStateOf(null)
        private set

    /** 마지막 설치 시도의 결과. `null` 이면 아직 동의하지 않았다. */
    var installResult: UpdateInstallResult? by mutableStateOf(null)
        private set

    /** 진행 중인 작업 때문에 설치를 미룬 사유. `null` 이면 미룬 적이 없다. */
    var deferredReason: String? by mutableStateOf(null)
        private set

    /** 내려받아 검증하는 중인가. 같은 동의를 두 번 실행하지 않게 한다. */
    var isInstalling: Boolean by mutableStateOf(false)
        private set

    /**
     * 시작 직후 한 번 확인하고 이후 [settings] 주기마다 확인한다 (결정 D14).
     *
     * **끄면 어느 확인도 하지 않는다** — 요청만 참는 것이 아니라 UseCase 자체를 부르지 않는다
     * (결정 D7). 취소는 호출부(`LaunchedEffect`)가 하며 이 함수는 취소를 삼키지 않는다.
     */
    suspend fun runChecks(settings: UpdateCheckSettings) {
        if (!settings.enabled) return
        val interval = intervalMillisOf(settings)
        while (true) {
            checkOnce()
            awaitNextCheck(interval)
        }
    }

    /**
     * 사용자가 동의한 설치를 시작한다.
     *
     * 진행 중인 작업이 있으면 **UseCase 를 부르지 않고** 사유만 남긴다 — 취소가 아니라 보류라서
     * 안내는 그대로 남고, 작업이 끝나면 다시 누를 수 있다.
     */
    fun install() {
        val release = availableRelease
        val blocked = installBlockedReason()
        when {
            release == null || isInstalling -> Unit
            blocked != null -> deferredReason = blocked
            else -> startInstall(release)
        }
    }

    /** 안내를 접는다. 다음 주기 확인이 새 버전을 다시 찾으면 배너가 돌아온다. */
    fun dismiss() {
        availableRelease = null
        installResult = null
        deferredReason = null
    }

    private fun startInstall(release: AvailableRelease) {
        deferredReason = null
        installResult = null
        isInstalling = true
        scope.launch {
            try {
                installResult = updates.install.execute(release)
            } finally {
                isInstalling = false
            }
        }
    }

    private suspend fun checkOnce() {
        val result = updates.check.execute()
        lastCheckResult = result
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> availableRelease = result.release
            // 최신임이 **확인된** 경우에만 안내를 거둔다. 실패·릴리즈 없음은 아무것도 바꾸지 않는다.
            UpdateCheckResult.UpToDate -> availableRelease = null
            UpdateCheckResult.NoRelease, is UpdateCheckResult.CheckFailed -> Unit
        }
    }
}

/**
 * 확인 주기(밀리초).
 *
 * 뜻이 있는 범위 밖의 값은 기본 주기로 읽는다 — 판단 기준은 domain 이 선언한
 * [UpdateCheckSettings.INTERVAL_HOURS_RANGE] 이고, 설정 파일을 읽는 codec 도 같은 기준을 쓴다.
 */
internal fun intervalMillisOf(settings: UpdateCheckSettings): Long {
    val hours = settings.intervalHours
        .takeIf { it in UpdateCheckSettings.INTERVAL_HOURS_RANGE }
        ?: UpdateCheckSettings.DEFAULT.intervalHours
    return hours * MILLIS_PER_HOUR
}
