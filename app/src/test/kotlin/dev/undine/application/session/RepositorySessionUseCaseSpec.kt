package dev.undine.application.session

import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.WindowBounds
import dev.undine.domain.WorkingTreeStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")
private val GAMMA = RepositoryPath("/repositories/gamma")
private val MISSING = RepositoryPath("/repositories/missing")

class RepositorySessionUseCaseSpec : BehaviorSpec({

    given("열린 탭이 없는 세션") {
        `when`("두 저장소를 차례로 열면") {
            then("둘 모두 탭으로 남고 마지막 탭만 활성 자원을 가진다") {
                val repositories = SessionRepositoryGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)

                useCase.open(ALPHA)
                val snapshot = useCase.open(BETA)

                snapshot.activePath shouldBe BETA
                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                snapshot.tabs.map { it.resourcesLoaded } shouldContainExactly listOf(false, true)
                repositories.openedPaths shouldContainExactly listOf(ALPHA, BETA)
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA)
                settings.stored.activeTabIndex shouldBe 1
            }
        }
    }

    given("세 탭이 열린 세션") {
        `when`("가장 최근에 쓰지 않은 탭으로 전환하면") {
            then("탭 상태는 남고 이전 활성 탭의 자원만 회수한다") {
                val repositories = SessionRepositoryGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                useCase.open(ALPHA)
                useCase.open(BETA)
                useCase.open(GAMMA)

                val snapshot = useCase.activate(ALPHA)

                snapshot.activePath shouldBe ALPHA
                snapshot.tabs.map { it.resourcesLoaded } shouldContainExactly listOf(true, false, false)
                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA, GAMMA)
            }
        }
    }

    given("활성 탭과 비활성 탭이 있는 세션") {
        `when`("비활성 탭을 닫으면") {
            then("현재 저장소를 닫지 않고 설정 목록에서만 제거한다") {
                val repositories = SessionRepositoryGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                useCase.open(ALPHA)
                useCase.open(BETA)

                val snapshot = useCase.close(ALPHA)

                repositories.closeCount shouldBe 0
                snapshot.activePath shouldBe BETA
                snapshot.tabs.map { it.path } shouldContainExactly listOf(BETA)
                settings.stored.openTabs shouldContainExactly listOf(BETA)
            }
        }

        `when`("활성 탭을 닫으면") {
            then("JGit 자원을 즉시 닫고 다음 탭을 활성화한다") {
                val repositories = SessionRepositoryGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                useCase.open(ALPHA)
                useCase.open(BETA)

                val snapshot = useCase.close(BETA)

                repositories.closeCount shouldBe 1
                repositories.openedPaths shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                snapshot.activePath shouldBe ALPHA
                snapshot.tabs.single().resourcesLoaded shouldBe true
            }
        }
    }

    given("저장된 탭과 범위 밖 활성 인덱스") {
        `when`("세션을 복원하면") {
            then("인덱스를 0으로 클램프하고 사라진 경로도 오류 탭으로 남긴다") {
                val repositories = SessionRepositoryGateway(failingPaths = setOf(MISSING))
                val settings = SessionSettingsGateway(openTabs = listOf(MISSING, BETA), activeTabIndex = 99)
                val useCase = RepositorySessionUseCase(repositories, settings)

                val snapshot = useCase.restore()

                snapshot.activePath shouldBe MISSING
                snapshot.tabs.map { it.availability } shouldContainExactly listOf(
                    TabAvailability.MissingPath,
                    TabAvailability.Available,
                )
                repositories.openedPaths shouldContainExactly listOf(MISSING)
                settings.stored.activeTabIndex shouldBe 0
            }
        }
    }

    given("열지 않은 경로") {
        `when`("활성화를 요청하면") {
            then("상태 위반으로 실패한다") {
                val useCase = RepositorySessionUseCase(SessionRepositoryGateway(), SessionSettingsGateway())

                shouldThrow<IllegalArgumentException> { useCase.activate(ALPHA) }
            }
        }
    }
})

private class SessionRepositoryGateway(
    private val failingPaths: Set<RepositoryPath> = emptySet(),
) : RepositoryGateway {
    val openedPaths = mutableListOf<RepositoryPath>()
    var closeCount = 0
        private set

    override suspend fun open(path: RepositoryPath): OpenedRepository {
        openedPaths += path
        if (path in failingPaths) {
            throw UndineException.InvalidRepositoryPath(
                path.value,
                UndineException.InvalidRepositoryPath.Reason.NOT_FOUND,
            )
        }
        return OpenedRepository(RepositoryState.NORMAL, RefName("refs/heads/main"))
    }

    override suspend fun status(): WorkingTreeStatus = error("not used")

    override suspend fun close() {
        closeCount++
    }
}

private class SessionSettingsGateway(
    openTabs: List<RepositoryPath> = emptyList(),
    activeTabIndex: Int = 0,
) : SettingsGateway {
    var stored = Settings(
        recentRepositories = emptyList(),
        theme = ThemeMode.SYSTEM,
        window = WindowBounds(1280, 800, maximized = false),
        openTabs = openTabs,
        activeTabIndex = activeTabIndex,
    )
        private set

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        stored = transform(stored)
    }
}
