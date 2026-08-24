package dev.undine.presentation.welcome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.welcome.CloneOutcome
import dev.undine.domain.Progress
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * Welcome 화면 상태 홀더 — 최근 목록·진행률·안내를 소유하고 [WelcomeActions] 만 호출한다.
 *
 * Gateway 를 직접 주입받지 않는다 ([[architecture-layers]]). 열린 저장소를 셸에 전달하는 일은
 * [onRepositoryOpened] 콜백으로 열어 두고 실제 연결은 UND-26 이 한다.
 *
 * @param scope 화면 수명에 묶인 스코프. `GlobalScope` 를 쓰지 않는다 (kotlin-idioms 9항).
 * @param onRepositoryOpened 열기·클론이 성공한 경로. UND-26 이 셸 선택 상태로 옮긴다.
 * @param pathExists 최근 경로의 현재 존재 여부. 파일 시스템 판정을 파라미터로 열어 테스트가 고정한다.
 */
@Stable
@Suppress("TooManyFunctions") // 열기·최근 목록·clone 입력·clone 수명이 한 화면의 상태 전이다.
class WelcomeState(
    private val actions: WelcomeActions,
    private val scope: CoroutineScope,
    private val onRepositoryOpened: (RepositoryPath) -> Unit,
    private val pathExists: (RepositoryPath) -> Boolean = { File(it.value).isDirectory },
) {
    var screenState: WelcomeScreenState by mutableStateOf(WelcomeScreenState())
        private set

    private var cloneJob: Job? = null

    /** 최근 목록을 다시 읽어 존재 여부까지 갱신한다. 화면이 처음 붙을 때와 창이 다시 활성화될 때 부른다. */
    fun refresh() {
        scope.launch { reloadRecent() }
    }

    /**
     * 로컬 저장소를 연다. **성공해야** 최근 목록 맨 앞으로 올라가고 전환 이벤트가 나간다.
     * 실패는 사유별 안내로만 남기고 예외를 밖으로 흘리지 않는다 — 스코프가 죽으면 화면이 멈춘다.
     */
    fun open(path: RepositoryPath) {
        scope.launch {
            try {
                actions.openRepository.execute(path)
                screenState = screenState.copy(notice = null)
                reloadRecent()
                onRepositoryOpened(path)
            } catch (failure: UndineException) {
                screenState = screenState.copy(notice = openNoticeFor(failure))
            }
        }
    }

    /** 최근 목록에서 항목을 지운다 — 사라진 경로를 앱이 알아서 지우지 않으므로 이 경로만이 삭제 수단이다. */
    fun forget(path: RepositoryPath) {
        scope.launch {
            val remaining = actions.forgetRecentRepository.execute(path)
            screenState = screenState.copy(recentRepositories = remaining.toRecentRepositories(pathExists))
        }
    }

    /** clone 입력란의 원격 주소를 갱신한다. 입력 중인 글자도 화면 상태라 화면이 아니라 여기가 소유한다. */
    fun changeCloneUrl(url: String) {
        screenState = screenState.copy(cloneUrl = url)
    }

    /** clone 입력란의 대상 디렉터리 경로를 갱신한다. */
    fun changeCloneTarget(target: String) {
        screenState = screenState.copy(cloneTarget = target)
    }

    /**
     * 원격 저장소를 클론한다. 진행 중 재요청은 무시한다 — 같은 대상에 두 clone 이 붙으면
     * 한쪽의 정리가 다른 쪽이 받은 파일을 지운다.
     *
     * `depth`(shallow) 옵션은 제공하지 않는다 — `RemoteGateway.clone` 계약에 없고 계약 확장은
     * 이 티켓 소유가 아니다 (wave 3 결정 A4).
     */
    fun startClone(url: String, target: String) {
        if (screenState.cloning) return
        val into = RepositoryPath(target)
        screenState = screenState.copy(cloning = true, cloneProgress = null, notice = null)
        cloneJob = scope.launch { runClone(url, into) }
    }

    /** 진행 중인 clone 을 중단한다. 취소는 성공이 아니므로 전환 이벤트도 최근 목록 저장도 하지 않는다. */
    fun cancelClone() {
        cloneJob?.cancel()
    }

    fun dismissNotice() {
        screenState = screenState.copy(notice = null)
    }

    private suspend fun runClone(url: String, into: RepositoryPath) {
        var cleanupFailure: RepositoryPath? = null
        // 이 실행분만 화면을 갱신한다 — 취소·완료 뒤 늦게 도착한 진행률이 다음 clone 표시를 흔들지 않는다.
        val job = coroutineContext[Job]
        try {
            val outcome = actions.cloneRepository.execute(
                url = url,
                into = into,
                onProgress = { progress -> publishProgress(job, progress) },
                onCleanupFailed = { cleanupFailure = it },
            )
            when (outcome) {
                is CloneOutcome.Cloned -> {
                    reloadRecent()
                    onRepositoryOpened(outcome.path)
                }
                CloneOutcome.TargetNotEmpty ->
                    screenState = screenState.copy(notice = WelcomeNotice.TargetNotEmpty)
            }
        } catch (failure: UndineException) {
            screenState = screenState.copy(notice = cloneNoticeFor(failure))
        } finally {
            // 취소도 이 경로로 온다 — CancellationException 은 잡지 않고 그대로 전파한다 (kotlin-idioms 12항).
            // 정리 실패는 원인 예외보다 사용자가 할 일이 분명하므로 안내를 덮어쓴다.
            val leftover = cleanupFailure
            screenState = screenState.copy(
                cloning = false,
                cloneProgress = null,
                notice = if (leftover == null) screenState.notice else WelcomeNotice.CleanupFailed(leftover),
            )
        }
    }

    /**
     * 진행률을 **화면 스코프로 옮겨** 반영한다.
     *
     * `RemoteGateway` 는 IO 스레드에서 콜백을 부르므로 여기서 그대로 상태를 쓰면 Compose 상태를
     * UI 스레드 밖에서 바꾸게 된다. [scope] 는 화면이 만든 컴포지션 스코프라 그 디스패처가 곧 UI
     * 컨텍스트다 — 다른 상태 갱신이 이미 이 스코프 위에서 일어나므로 진행률만 예외로 두지 않는다.
     *
     * 옮기는 사이 clone 이 끝나거나 취소될 수 있어, 값을 쓰기 직전에 **그 실행분이 아직 살아 있는지**
     * 다시 본다 — 죽은 실행분의 늦은 진행률은 버린다.
     */
    private fun publishProgress(job: Job?, progress: Progress) {
        scope.launch {
            if (job?.isActive != true) return@launch
            screenState = screenState.copy(cloneProgress = progress)
        }
    }

    private suspend fun reloadRecent() {
        val paths = actions.loadRecentRepositories.execute()
        screenState = screenState.copy(recentRepositories = paths.toRecentRepositories(pathExists))
    }
}

/** 저장 순서(앞이 최신)를 유지한 채 표시 시점의 존재 여부만 덧붙인다. */
private fun List<RepositoryPath>.toRecentRepositories(
    pathExists: (RepositoryPath) -> Boolean,
): List<RecentRepository> = map { RecentRepository(path = it, available = pathExists(it)) }

/** 열기 실패 → 안내. 네 경로 사유로 설명되지 않는 실패도 조용히 삼키지 않는다. */
private fun openNoticeFor(failure: UndineException): WelcomeNotice = when (failure) {
    is UndineException.InvalidRepositoryPath -> WelcomeNotice.OpenFailed(failure.reason)
    is UndineException.AuthenticationFailed -> WelcomeNotice.AuthenticationFailed
    else -> WelcomeNotice.OpenFailedUnexpectedly
}

/** clone 실패 → 안내. 원격 URL·자격증명은 어느 쪽에도 담기지 않는다 (`credential-handling` 2항). */
private fun cloneNoticeFor(failure: UndineException): WelcomeNotice = when (failure) {
    is UndineException.AuthenticationFailed -> WelcomeNotice.AuthenticationFailed
    else -> WelcomeNotice.CloneFailed
}
