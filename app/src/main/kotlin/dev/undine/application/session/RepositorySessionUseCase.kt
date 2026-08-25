package dev.undine.application.session

import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UndineException

/** 활성 탭에 연결된 JGit 자원은 열려 있는 상태다. */
enum class TabAvailability {
    Available,
    MissingPath,
}

/** 영속 경로와 별개로 세션 동안만 필요한 탭 자원 상태다. */
data class TabSession(
    val path: RepositoryPath,
    val availability: TabAvailability,
    val resourcesLoaded: Boolean,
)

/** presentation 이 탭 상태로 바꿔 그릴 수 있는 읽기 전용 세션 스냅샷. */
data class RepositorySessionSnapshot(
    val tabs: List<TabSession>,
    val activePath: RepositoryPath?,
)

/**
 * 저장소 탭의 활성화와 설정 영속화를 조정한다.
 *
 * JGit 핸들 전환은 [RepositoryGateway] 뒤의 RepositoryHolder가 원자적으로 수행한다. 이 UseCase는
 * 별도 락을 갖지 않으며, 활성 탭 하나만 자원을 유지하는 정책으로 비활성 탭과 LRU 후보의 자원을
 * 즉시 회수한다.
 */
class RepositorySessionUseCase(
    private val repositoryGateway: RepositoryGateway,
    private val settingsGateway: SettingsGateway,
) {

    private val tabs = mutableListOf<TabSession>()
    private var activePath: RepositoryPath? = null

    /** 새 저장소를 탭으로 열고 활성화한다. 이미 열린 경로면 중복 탭을 만들지 않는다. */
    suspend fun open(path: RepositoryPath): RepositorySessionSnapshot {
        if (tabs.none { it.path == path }) {
            repositoryGateway.open(path)
            tabs += TabSession(path, TabAvailability.Available, resourcesLoaded = false)
            return markActive(path, TabAvailability.Available)
        }
        return activate(path)
    }

    /** 기존 탭을 활성화한다. 이전 활성 탭의 무거운 자원은 Holder가 해제한다. */
    suspend fun activate(path: RepositoryPath): RepositorySessionSnapshot {
        val index = tabs.indexOfFirst { it.path == path }
        require(index >= 0) { "열려 있지 않은 탭입니다: $path" }

        val availability = try {
            repositoryGateway.open(path)
            TabAvailability.Available
        } catch (failure: UndineException.InvalidRepositoryPath) {
            if (failure.reason != UndineException.InvalidRepositoryPath.Reason.NOT_FOUND) throw failure
            TabAvailability.MissingPath
        }
        return markActive(path, availability)
    }

    private suspend fun markActive(
        path: RepositoryPath,
        availability: TabAvailability,
    ): RepositorySessionSnapshot {
        activePath = path
        tabs.replaceAll { tab ->
            when {
                tab.path != path -> tab.copy(resourcesLoaded = false)
                else -> tab.copy(
                    availability = availability,
                    resourcesLoaded = availability == TabAvailability.Available,
                )
            }
        }
        persist()
        return snapshot()
    }

    /** 탭을 닫고, 활성 탭이었다면 그 핸들을 즉시 닫은 뒤 남은 탭을 활성화한다. */
    suspend fun close(path: RepositoryPath): RepositorySessionSnapshot =
        tabs.indexOfFirst { it.path == path }.let { index ->
            if (index < 0) snapshot() else closeAt(index)
        }

    private suspend fun closeAt(index: Int): RepositorySessionSnapshot {
        val closingPath = tabs[index].path
        val wasActive = closingPath == activePath
        if (wasActive) {
            repositoryGateway.close()
            activePath = null
        }
        tabs.removeAt(index)
        val next = if (wasActive) tabs.getOrNull(index) ?: tabs.lastOrNull() else null
        return when {
            !wasActive || next == null -> persistSnapshot()
            else -> activate(next.path)
        }
    }

    private suspend fun persistSnapshot(): RepositorySessionSnapshot {
        persist()
        return snapshot()
    }

    /** Settings가 제공한 탭 목록을 복원한다. 존재하지 않는 경로도 목록에서 지우지 않는다. */
    suspend fun restore(): RepositorySessionSnapshot {
        val settings = settingsGateway.load()
        tabs.clear()
        activePath = null
        tabs += settings.openTabs.map { path ->
            TabSession(path, TabAvailability.Available, resourcesLoaded = false)
        }
        if (tabs.isEmpty()) return snapshot()

        val restoredIndex = settings.activeTabIndex.takeIf { it in tabs.indices } ?: 0
        return activate(tabs[restoredIndex].path)
    }

    private suspend fun persist() {
        val openPaths = tabs.map(TabSession::path)
        val activeIndex = activePath?.let(openPaths::indexOf)?.takeIf { it >= 0 } ?: 0
        settingsGateway.update { settings ->
            settings.copy(openTabs = openPaths, activeTabIndex = activeIndex)
        }
    }

    private fun snapshot(): RepositorySessionSnapshot = RepositorySessionSnapshot(
        tabs = tabs.toList(),
        activePath = activePath,
    )
}
