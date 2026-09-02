package dev.undine.presentation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.session.RepositorySessionSnapshot
import dev.undine.application.session.RepositorySessionUseCase
import dev.undine.application.session.TabAvailability
import dev.undine.application.session.TabId
import dev.undine.application.session.TabSession
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionKey
import dev.undine.presentation.shell.ActiveRepository
import dev.undine.presentation.tabs.RepositoryTabsState
import dev.undine.presentation.tabs.TabCloseRequest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * 탭 세션과 화면을 잇는 presentation 배선.
 *
 * [RepositorySessionUseCase] 가 만든 전이 결과를 탭 막대([tabs])·셸 선택([onActiveRepository])·
 * 활성 저장소의 되돌리기 범위([activeUndoScope]) 세 곳에 **한 번에** 옮긴다.
 *
 * **자기 락을 두지 않는다** (결정 A-N1). 전이의 직렬화는 세션 홀더가 소유하고, 이 클래스는 반영을
 * UseCase 가 임계 구역 안에서 부르는 `apply` 훅에 얹는다 — 반환 뒤에 반영하면 두 전이의 재개
 * 순서가 완료 순서와 어긋나 셸 선택과 화면 컨텍스트가 서로 다른 저장소를 가리킬 수 있다.
 *
 * **반영은 전이를 띄운 디스패처(UI)로 되돌아가서 한다** (결정 G41). 임계 구역은 Git I/O 경계
 * (`Dispatchers.IO`) 위에서 도는데 Compose 상태를 거기서 바꾸면 UI 가 자기 스레드 밖에서 갱신된다.
 * 그렇다고 스냅샷만 만들고 락을 놓은 뒤 반영하면 두 전이의 반영 순서가 완료 순서와 뒤집혀, 이
 * 배선이 막으려던 "셸 선택과 컨텍스트가 다른 저장소를 가리킨다" 가 그대로 돌아온다. 그래서
 * **락을 쥔 채** UI 디스패처로 건너뛰어 반영을 끝내고 돌아온다.
 *
 * 화면은 전이를 띄우기만 하고 결과를 동기로 기다리지 않는다 (`reportingFailure` 의 `launch`) —
 * UI 스레드가 이 락을 기다리는 경로가 없어야 위 왕복이 교착하지 않는다.
 *
 * **되돌리기 범위의 수명은 탭이다.** 범위를 가르는 기준은 `baseline`(브랜치·HEAD)이 아니라 홀더가
 * 정규화해 돌려준 [RepositorySessionKey] 다 — baseline 은 같은 커밋을 가리키는 clone 둘에서 같으므로
 * 구분에 쓸 수 없다 (결정 G29). 그래서
 * - 저장소를 오가도 범위를 버리지 않는다. 돌아오면 그 저장소의 이력이 그대로 있다.
 * - 같은 저장소를 연 중복 탭은 **하나의 범위를 공유**한다. 탭마다 이력이 갈리면 사용자는 자기가
 *   어느 탭에서 한 일인지로 되돌리기를 찾아야 한다.
 * - 조회 실패는 범위를 지우는 사유가 아니다. 범위는 **마지막 참조 탭이 닫힐 때만** 사라진다.
 *
 * @param createUndoScope 저장소 하나가 쓸 새 되돌리기 범위. 타입을 열어 둔 것은 이 배선이
 *   `AppComponent` 의 내부 타입을 알 필요가 없기 때문이다 — 범위의 조립은 DI 가 소유한다.
 * @param onActiveRepository 활성 탭이 가리키는 저장소. **셸 선택과 화면 컨텍스트가 함께** 이 값을
 *   따른다 — 임계 구역 안에서 불리므로, 이 훅이 끝난 시점에 화면의 어느 부분도 지나간 저장소를
 *   가리키지 않아야 한다 (결정 G42). 조작 대상과 탭이 가리키는 저장소를 가른 [ActiveRepository] 를
 *   넘긴다 — 경로를 잃은 탭도 셸이 그릴 것을 알아야 탭 막대가 남는다 (UND-83). 저장소마다 다시
 *   읽어야 하는 값(브랜치·태그·원격)의 **조회**는 여기서 하지 않는다. 같은 Git 경계를 지나므로
 *   구역 안에서 부르면 교착한다.
 */
@Stable
class RepositorySessionDriver<UndoScope>(
    private val sessions: RepositorySessionUseCase,
    private val createUndoScope: () -> UndoScope,
    private val onActiveRepository: (ActiveRepository) -> Unit,
) {

    val tabs: RepositoryTabsState = RepositoryTabsState(EMPTY_SNAPSHOT)

    /** 저장소마다 하나인 되돌리기 범위. 키는 홀더가 준 세션 키다. */
    private val undoScopes = mutableMapOf<RepositorySessionKey, UndoScope>()

    /**
     * 탭이 마지막으로 쓴 세션 키. **한 번 알면 탭이 닫힐 때까지 기억한다** — LRU 회수로 핸들을 놓은
     * 탭은 스냅샷에 키가 없는데, 그때 기억을 버리면 열려 있는 탭의 이력이 사라진다.
     */
    private val tabKeys = mutableMapOf<TabId, RepositorySessionKey>()

    /**
     * 저장소에 매이지 않은 범위. 열린 탭이 없거나 활성 탭의 경로가 사라졌을 때 쓴다 — 저장소가 없으면
     * 기록할 변경도 없으므로 이 범위에는 아무것도 쌓이지 않는다.
     */
    private val detachedScope: UndoScope = createUndoScope()

    private var activeUndoScopeState by mutableStateOf(detachedScope)

    /** **지금** 활성 저장소의 되돌리기 범위. 탭을 바꾸면 그 저장소의 것으로 바뀐다. */
    val activeUndoScope: UndoScope get() = activeUndoScopeState

    private var pendingCloseState by mutableStateOf<TabId?>(null)

    /** 사용자의 확인을 기다리는 탭. 확인 전에는 닫지 않는다. */
    val pendingClose: TabId? get() = pendingCloseState

    /** 저장된 탭 목록을 복원한다. 앱 시작 시 한 번 부른다. */
    suspend fun restore() {
        sessions.restore(reflectingOnCaller())
    }

    /** 저장소를 새 탭으로 연다. 같은 경로여도 새 탭이다 — 중복 탭은 사용자의 선택이다. */
    suspend fun open(path: RepositoryPath) {
        sessions.open(path, reflectingOnCaller())
    }

    suspend fun activate(tabId: TabId) {
        sessions.activate(tabId, reflectingOnCaller())
    }

    suspend fun close(tabId: TabId) {
        sessions.close(tabId, reflectingOnCaller())
    }

    /** 활성 탭을 닫는다. "저장소 닫기" 명령이 부르는 경로다. */
    suspend fun closeActive() {
        val active = tabs.activeTabId ?: return
        requestClose(tabs.requestClose(active))
    }

    /**
     * 탭 막대가 낸 닫기 요청을 처리한다.
     *
     * 확인이 필요한 요청은 **여기서 닫지 않고** [pendingClose] 로 올린다 — 진행 중인 원격 작업을
     * 사용자 확인 없이 끊지 않는다. 요청을 조용히 버리지도 않는다.
     */
    suspend fun requestClose(request: TabCloseRequest) {
        when (request) {
            is TabCloseRequest.Ready -> close(request.tabId)
            is TabCloseRequest.ConfirmationRequired -> pendingCloseState = request.tabId
        }
    }

    /** 확인을 받은 탭을 닫는다. */
    suspend fun confirmPendingClose() {
        val pending = pendingCloseState ?: return
        pendingCloseState = null
        close(pending)
    }

    /** 확인을 취소한다. 탭은 그대로 남는다. */
    fun dismissPendingClose() {
        pendingCloseState = null
    }

    /**
     * 임계 구역 안에서 불릴 반영 훅을 만든다.
     *
     * **전이를 띄운 디스패처를 여기서 붙잡는다.** 이 배선의 진입점은 언제나 Compose 가 준
     * 스코프(`rememberCoroutineScope`·`LaunchedEffect`)에서 불리므로, 그 디스패처가 곧 화면을
     * 그리는 UI 디스패처다. 임계 구역 안에서 그리로 건너뛰어 반영하면 두 요구
     * (한 임계 구역 · Compose 스레드)를 모두 지킨다 — 어느 하나를 버리지 않는다 (결정 G41).
     */
    private suspend fun reflectingOnCaller(): suspend (RepositorySessionSnapshot) -> Unit {
        val callerDispatcher: CoroutineContext =
            currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
        return { snapshot -> withContext(callerDispatcher) { adopt(snapshot) } }
    }

    /**
     * 전이 결과를 표시 상태로 옮긴다. **UseCase 의 임계 구역 안에서, 전이를 띄운 디스패처로 불린다.**
     *
     * 순서가 규칙이다 — 살아 있는 탭을 먼저 정하고, 그 탭들이 참조하는 키만 남긴 뒤, 남은 키로
     * 범위를 고른다. 범위 정리를 먼저 하면 이번 전이가 연 탭의 키가 아직 장부에 없어 방금 만든
     * 범위를 지운다.
     */
    private fun adopt(snapshot: RepositorySessionSnapshot) {
        val liveTabs = snapshot.tabs.mapTo(mutableSetOf(), TabSession::id)
        snapshot.tabs.forEach { tab -> tab.sessionKey?.let { key -> tabKeys[tab.id] = key } }
        tabKeys.keys.retainAll(liveTabs)
        // 참조하는 탭이 하나도 남지 않은 저장소의 이력만 버린다 (마지막 참조 규칙).
        undoScopes.keys.retainAll(tabKeys.values.toSet())
        pendingCloseState = pendingCloseState?.takeIf { it in liveTabs }

        tabs.apply(snapshot)
        val active = snapshot.activeTabId?.let { id -> snapshot.tabs.firstOrNull { it.id == id } }
        // **기억한 키를 그대로 쓰지 않는다.** 키가 스냅샷에서 사라지는 경우는 둘인데 뜻이 다르다:
        // LRU 회수는 경로가 멀쩡한 채 핸들만 놓은 것이고(이력은 그 탭의 것으로 남아야 한다),
        // `MissingPath` 는 저장소 자체가 사라진 것이다. 둘을 같이 다루면 한쪽이 깨진다 —
        // 기억을 늘 쓰면 사라진 저장소의 탭이 **직전 저장소의 핸들 위에서** 조작을 실행하고,
        // 기억을 늘 버리면 잠깐 회수된 탭의 이력을 잃는다. 그래서 **기억은 남기되 라우팅은 막는다.**
        val activeKey = tabKeys[active?.id]
            ?.takeIf { active?.availability == TabAvailability.Available }
        activeUndoScopeState = activeKey
            ?.let { key -> undoScopes.getOrPut(key, createUndoScope) }
            ?: detachedScope
        onActiveRepository(activeRepositoryOf(active, routable = activeKey != null))
    }
}

/**
 * 활성 탭을 화면이 보는 세 갈래로 옮긴다 (UND-83).
 *
 * 라우팅이 막힌 탭을 "저장소 없음" 으로 뭉뚱그리지 않는다 — 그러면 셸이 사라져 탭 막대까지 함께
 * 없어진다. 탭은 남아 있고 조작할 곳만 없다는 사실을 [ActiveRepository.Unavailable] 이 그대로 나른다.
 */
private fun activeRepositoryOf(active: TabSession?, routable: Boolean): ActiveRepository = when {
    active == null -> ActiveRepository.None
    routable -> ActiveRepository.Operable(active.path)
    else -> ActiveRepository.Unavailable(active.path)
}

private val EMPTY_SNAPSHOT = RepositorySessionSnapshot(tabs = emptyList(), activeTabId = null)
