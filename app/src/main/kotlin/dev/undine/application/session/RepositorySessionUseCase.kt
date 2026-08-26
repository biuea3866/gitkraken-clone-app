package dev.undine.application.session

import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionGateway
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.RepositorySessions
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 동시에 JGit 핸들을 유지하는 탭 세션의 상한.
 *
 * 초과분은 **가장 오래 쓰지 않은 순**으로 회수한다. 활성 탭은 항상 유지 목록의 맨 뒤라 회수 대상이
 * 되지 않는다. 4는 "오가는 저장소는 보통 몇 개인가" 에 대한 값이고, 늘리면 열린 파일 핸들이 늘어난다.
 */
const val MAX_LOADED_SESSIONS: Int = 4

/**
 * 탭 하나를 가리키는 식별자.
 *
 * **경로는 탭의 식별자가 아니다.** 같은 저장소를 두 탭으로 여는 것은 사용자의 선택이고
 * (`Settings.openTabs` 가 중복을 지우지 않는 이유다), 그 두 탭은 선택 커밋·스크롤·필터를 각자
 * 유지해야 한다. 경로로 탭을 찾으면 두 탭이 서로의 화면 상태를 덮어쓴다.
 */
@JvmInline
value class TabId(val value: Long)

/** 활성 탭에 연결된 JGit 자원은 열려 있는 상태다. */
enum class TabAvailability {
    Available,
    MissingPath,
}

/** 영속 경로와 별개로 세션 동안만 필요한 탭 자원 상태다. */
data class TabSession(
    val id: TabId,
    val path: RepositoryPath,
    val availability: TabAvailability,
    val resourcesLoaded: Boolean,
)

/** presentation 이 탭 상태로 바꿔 그릴 수 있는 읽기 전용 세션 스냅샷. */
data class RepositorySessionSnapshot(
    val tabs: List<TabSession>,
    val activeTabId: TabId?,
)

/**
 * 저장소 탭의 활성화·자원 회수·설정 영속화를 조정한다.
 *
 * **자원 정책은 두 층이다.**
 * 1. *완전 로드*(`resourcesLoaded`)는 활성 탭 하나뿐이다 — 탭을 벗어나는 즉시 무거운 캐시를 놓는다.
 * 2. *세션 핸들*은 [MAX_LOADED_SESSIONS] 개까지만 유지한다. 상한을 넘으면 가장 오래 쓰지 않은
 *    세션을 [RepositorySessions.release] 로 회수한다. 탭 자체와 그 화면 상태는 남는다.
 *
 * **탭과 세션은 다른 것이다.** 탭은 [TabId] 로 식별하고 저장소 세션은 Gateway 가 돌려준
 * [RepositorySessionKey] 로 식별한다. 같은 저장소를 가리키는 탭이 둘이면 세션 핸들은 하나를
 * 공유하므로, 한 탭을 닫아도 그 저장소를 가리키는 탭이 남아 있으면 핸들을 회수하지 않는다.
 *
 * **저장 목록은 사용자의 것이다** (결정 C2 정정 1). 중복 경로를 접거나 상한으로 자르거나 활성
 * 인덱스를 다시 계산하지 않는다 — 복원 직후 `persist` 로 축소된 목록을 덮어쓰면 사용자가 연 탭이
 * 영구히 사라진다. 경로가 사라진 탭도 목록에서 지우지 않고 [TabAvailability.MissingPath] 로 표시한다.
 *
 * **락 소유.** 이 UseCase 는 자기 락을 두지 않는다 (결정 A-N1). 직렬화는 [RepositorySessionGateway]
 * 구현(`RepositoryHolder`)이 소유하고, 이 UseCase 는 전이 **전체**를
 * [RepositorySessionGateway.transition] 안에서 끝낸다 — 탭 장부 전이·설정 영속화·실패 보상까지
 * 같은 임계 구역이다 (결정 C2 정정 5). 장부만 밖에 남기면 gateway 호출 사이에 다른 전이가 끼어들어
 * 앞선 전이의 복원점이 뒤 전이의 탭·핸들을 덮는다.
 */
class RepositorySessionUseCase(
    private val sessionGateway: RepositorySessionGateway,
    private val settingsGateway: SettingsGateway,
) {

    private val tabs = mutableListOf<TabSession>()
    private var activeTabId: TabId? = null

    /** 탭 → 그 탭이 쓰는 세션 키. 핸들이 없는 탭(미활성·경로 없음)은 여기에 없다. */
    private val sessionKeys = mutableMapOf<TabId, RepositorySessionKey>()

    /** 핸들을 유지 중인 세션의 LRU 순서. 앞이 가장 오래됐고 맨 뒤가 활성 세션이다. */
    private val loadedSessions = LinkedHashSet<RepositorySessionKey>()

    private var lastTabId = 0L

    /** 새 저장소를 탭으로 열고 활성화한다. 이미 열린 경로여도 **새 탭**을 연다. */
    suspend fun open(path: RepositoryPath): RepositorySessionSnapshot =
        sessionGateway.transition { sessions ->
            compensating(sessions) {
                val opened =
                    TabSession(TabId(++lastTabId), path, TabAvailability.Available, resourcesLoaded = false)
                tabs += opened
                activateLocked(sessions, opened.id)
            }
        }

    /** 기존 탭을 활성화한다. 이전 활성 탭은 완전 로드를 놓고, 상한을 넘은 세션은 회수된다. */
    suspend fun activate(tabId: TabId): RepositorySessionSnapshot =
        sessionGateway.transition { sessions ->
            // 확인도 임계 구역 안이다 — 밖에서 보면 그 사이에 닫힌 탭을 열려 있다고 읽는다.
            require(tabs.any { it.id == tabId }) { "열려 있지 않은 탭입니다: $tabId" }
            compensating(sessions) { activateLocked(sessions, tabId) }
        }

    /** 탭을 닫고 그 탭의 JGit 자원을 즉시 해제한 뒤, 활성 탭이었다면 남은 탭을 활성화한다. */
    suspend fun close(tabId: TabId): RepositorySessionSnapshot =
        sessionGateway.transition { sessions -> compensating(sessions) { closeLocked(sessions, tabId) } }

    /** Settings가 제공한 탭 목록을 복원한다. 존재하지 않는 경로도 목록에서 지우지 않는다. */
    suspend fun restore(): RepositorySessionSnapshot =
        sessionGateway.transition { sessions -> compensating(sessions) { restoreLocked(sessions) } }

    private suspend fun activateLocked(
        sessions: RepositorySessions,
        tabId: TabId,
    ): RepositorySessionSnapshot {
        val requested = tabs.first { it.id == tabId }
        val openedKey = sessions.openInto(loadedSessions, requested.path)
        when (openedKey) {
            null -> {
                val stale = sessionKeys.remove(tabId)
                sessions.releaseUnreferenced(stale, sessionKeys, loadedSessions)
            }
            else -> sessionKeys[tabId] = openedKey
        }
        activeTabId = tabId
        tabs.replaceAll { tab ->
            when (tab.id) {
                tabId -> tab.copy(
                    availability = if (openedKey == null) TabAvailability.MissingPath else TabAvailability.Available,
                    resourcesLoaded = openedKey != null,
                )
                else -> tab.copy(resourcesLoaded = false)
            }
        }
        // 상한을 넘긴 핸들을 가장 오래 쓰지 않은 순으로 회수한다. 활성 세션은 방금 맨 뒤로 올라갔으므로
        // 대상이 되지 않는다. 탭과 그 화면 상태는 그대로 남는다.
        while (loadedSessions.size > MAX_LOADED_SESSIONS) {
            val leastRecentlyUsed = loadedSessions.first()
            loadedSessions -= leastRecentlyUsed
            sessionKeys.entries.removeIf { it.value == leastRecentlyUsed }
            sessions.release(leastRecentlyUsed)
        }
        persist()
        return currentSnapshot
    }

    private suspend fun closeLocked(
        sessions: RepositorySessions,
        tabId: TabId,
    ): RepositorySessionSnapshot {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return currentSnapshot

        val wasActive = tabId == activeTabId
        val closedKey = sessionKeys.remove(tabId)
        tabs.removeAt(index)
        sessions.releaseUnreferenced(closedKey, sessionKeys, loadedSessions)
        val next = if (wasActive) tabs.getOrNull(index) ?: tabs.lastOrNull() else null
        if (wasActive) activeTabId = null
        return when (next) {
            null -> {
                persist()
                currentSnapshot
            }
            else -> activateLocked(sessions, next.id)
        }
    }

    private suspend fun restoreLocked(sessions: RepositorySessions): RepositorySessionSnapshot {
        val settings = settingsGateway.load()
        if (tabs.isNotEmpty()) sessions.close()
        tabs.clear()
        activeTabId = null
        sessionKeys.clear()
        loadedSessions.clear()
        // 저장된 순서·중복을 그대로 옮긴다. 접거나 자르면 사용자가 연 탭을 복원 직후 잃는다.
        tabs += settings.openTabs.map { path ->
            TabSession(TabId(++lastTabId), path, TabAvailability.Available, resourcesLoaded = false)
        }
        if (tabs.isEmpty()) return currentSnapshot

        // 범위 밖 인덱스는 0으로 클램프한다 — UND-63이 정한 읽기 계약이고 오류가 아니다.
        val restoredIndex = settings.activeTabIndex.takeIf { it in tabs.indices } ?: 0
        return activateLocked(sessions, tabs[restoredIndex].id)
    }

    private suspend fun persist() {
        val openPaths = tabs.map(TabSession::path)
        val activeIndex = activeTabId?.let { id -> tabs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        settingsGateway.update { settings ->
            settings.copy(openTabs = openPaths, activeTabIndex = activeIndex)
        }
    }

    private val currentSnapshot: RepositorySessionSnapshot
        get() = RepositorySessionSnapshot(tabs = tabs.toList(), activeTabId = activeTabId)

    /**
     * 전이 도중 실패하면 **탭 장부·활성 탭·세션 핸들을 전이 이전으로 되돌린 뒤** 원인을 그대로 올린다.
     *
     * 설정 저장이 실패했을 때가 이 경계의 존재 이유다 — 메모리 상태만 바뀐 채 남으면 화면이 보여 주는
     * 탭과 다음 실행에 복원될 탭이 갈라진다.
     *
     * **실패 종류를 가리지 않는다.** 도메인 예외만 잡던 앞선 형태는 `SettingsGatewayImpl` 이 그대로
     * 올리는 [java.io.IOException] 과 취소를 놓쳐, 그 경로에서만 장부와 실제 핸들이 갈라졌다.
     * 여기서 삼키는 실패는 없다 — 되돌린 뒤 원인을 그대로 다시 던진다.
     *
     * **복원점은 임계 구역 안에서 뜬다.** 밖에서 뜨면 전이가 시작되기 전 상태를 들고 들어와, 그 사이에
     * 끝난 다른 전이의 탭·핸들을 되돌리기가 덮는다 (결정 C2 정정 5). 그래서 이 경계는 반드시
     * [RepositorySessionGateway.transition] 이 준 [sessions] 와 짝을 이룬다.
     */
    private suspend fun <T> compensating(sessions: RepositorySessions, block: suspend () -> T): T {
        val restorePoint = SessionRestorePoint(
            tabs = tabs.toList(),
            activeTabId = activeTabId,
            sessionKeys = sessionKeys.toMap(),
            loadedSessions = loadedSessions.toList(),
        )
        return runCatching { block() }
            .onFailure { failure -> rollbackTo(sessions, restorePoint, failure) }
            .getOrThrow()
    }

    /**
     * 장부와 **실제 세션 집합**을 함께 되돌린다. 장부만 되돌리면 이 전이가 새로 연 핸들이 장부 밖에
     * 남아 아무도 회수하지 않는다(누수). 되돌리기 뒤의 유지 목록은 Gateway 가 실제로 연 집합으로
     * 맞추고, 되살리지 못한 세션을 쓰던 탭은 실제 상태(`MissingPath`)로 내린다.
     *
     * 되돌리기는 [NonCancellable] 안에서 돈다. 취소된 코루틴에서는 Gateway 호출이 즉시 끊겨
     * 보상 자체가 실행되지 않는데, 그러면 취소 경로에서만 핸들이 새기 때문이다 (취소는 그대로 전파된다).
     * 보상마저 실패하면 원인 예외에 suppressed 로 붙이고, **되살렸다고 가정하지 않는다** — 세션을
     * 모두 닫고 장부를 비워 표시 상태를 실제 핸들에 맞춘다.
     */
    private suspend fun rollbackTo(
        sessions: RepositorySessions,
        restorePoint: SessionRestorePoint,
        failure: Throwable,
    ) {
        tabs.clear()
        tabs += restorePoint.tabs
        activeTabId = restorePoint.activeTabId
        sessionKeys.clear()
        sessionKeys += restorePoint.sessionKeys

        val restored = withContext(NonCancellable) {
            val active = restorePoint.activeTabId?.let(restorePoint.sessionKeys::get)
            runCatching { sessions.restoreSessions(restorePoint.loadedSessions, active) }
                .getOrElse { restoreFailure ->
                    // 되돌리기가 중간에 끊겼다 — 그 시점에 무엇이 열려 있는지 **알 수 없다.** 복원점
                    // 목록을 열려 있다고 믿으면 핸들 없는 탭을 다시 `Available` 로 보여 준다
                    // (결정 C2 정정 4 가 막으려던 바로 그 불일치다). 모르면 단정하지 않고 전부 닫아
                    // 장부를 비운다 — 닫힌 저장소는 다시 열 수 있지만 헛도는 표시는 사용자를 속인다.
                    failure.addSuppressed(restoreFailure)
                    runCatching { sessions.close() }.onFailure(failure::addSuppressed)
                    emptyList()
                }
        }
        loadedSessions.clear()
        loadedSessions += restored

        // 되살리지 못한 세션을 쓰던 탭을 "열려 있다" 고 표시하지 않는다 (결정 C2 정정 4). 복원점의
        // `Available`·`resourcesLoaded` 로 두면 화면이 없는 저장소를 열려 있다고 보여 준다 —
        // 탭 자체는 남기되(사용자의 목록이다) 상태는 실제 핸들에 맞춘다.
        val lostTabs = sessionKeys.filterValues { it !in loadedSessions }.keys.toSet()
        sessionKeys.keys.removeAll(lostTabs)
        tabs.replaceAll { tab ->
            tab.copy(
                availability = if (tab.id in lostTabs) TabAvailability.MissingPath else tab.availability,
                resourcesLoaded = tab.id == activeTabId && sessionKeys.containsKey(tab.id),
            )
        }
    }
}

/**
 * 세션을 열어 [loadedSessions] 의 LRU 순서 맨 뒤로 올리고 그 세션 키를 준다. 경로가 사라진 탭은
 * 실패가 아니라 `null` 이다 — 조용히 목록에서 버리지 않고 호출부가 표시 상태로 내린다.
 */
private suspend fun RepositorySessions.openInto(
    loadedSessions: LinkedHashSet<RepositorySessionKey>,
    path: RepositoryPath,
): RepositorySessionKey? = try {
    open(path).also { key ->
        loadedSessions -= key
        loadedSessions += key
    }
} catch (failure: UndineException.InvalidRepositoryPath) {
    if (failure.reason != UndineException.InvalidRepositoryPath.Reason.NOT_FOUND) throw failure
    null
}

/**
 * 세션 핸들은 **탭이 아니라 저장소마다 하나**다. 같은 저장소를 가리키는 다른 탭이 [referencedBy] 에
 * 남아 있으면 회수하지 않는다 — 회수하면 그 탭의 활성 핸들까지 닫힌다.
 */
private suspend fun RepositorySessions.releaseUnreferenced(
    key: RepositorySessionKey?,
    referencedBy: Map<TabId, RepositorySessionKey>,
    loadedSessions: LinkedHashSet<RepositorySessionKey>,
) {
    if (key == null || referencedBy.containsValue(key)) return
    loadedSessions -= key
    release(key)
}

/** 전이 시작 시점의 탭 장부 — 실패하면 이 값으로 되돌린다. */
private data class SessionRestorePoint(
    val tabs: List<TabSession>,
    val activeTabId: TabId?,
    val sessionKeys: Map<TabId, RepositorySessionKey>,
    val loadedSessions: List<RepositorySessionKey>,
)
