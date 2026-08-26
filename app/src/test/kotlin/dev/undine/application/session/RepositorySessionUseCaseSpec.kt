package dev.undine.application.session

import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionGateway
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.RepositorySessions
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.WindowBounds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")
private val GAMMA = RepositoryPath("/repositories/gamma")
private val MISSING = RepositoryPath("/repositories/missing")

/** 같은 저장소를 가리키는 다른 표기. Gateway 가 정규화해 [ALPHA] 와 같은 세션 키를 준다. */
private val ALPHA_ALIAS = RepositoryPath("/repositories/./alpha")

private fun repositoryAt(index: Int) = RepositoryPath("/repositories/repository-$index")

/**
 * 뒤 전이가 임계 구역에 **들어오지 못한 채** 머무는지 보기 위해 주는 시간.
 *
 * 직렬화가 깨져 있으면 이 창 안에서 뒤 전이가 세션을 열어 버리므로 단정이 실패한다. 통과를 위해
 * 기다리는 시간이 아니라 **실패를 드러내기 위해** 주는 시간이라, 길수록 검출력이 높고 느려질 뿐이다.
 */
private const val TRANSITION_OVERLAP_WINDOW_MILLIS = 200L

private fun keyOf(path: RepositoryPath) = RepositorySessionKey(path.value)

private val RepositorySessionSnapshot.lastTabId: TabId get() = tabs.last().id

private fun RepositorySessionSnapshot.tabIdAt(index: Int): TabId = tabs[index].id

class RepositorySessionUseCaseSpec : BehaviorSpec({

    given("열린 탭이 없는 세션") {
        `when`("두 저장소를 차례로 열면") {
            then("둘 모두 탭으로 남고 마지막 탭만 활성 자원을 가진다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)

                useCase.open(ALPHA)
                val snapshot = useCase.open(BETA)

                snapshot.activeTabId shouldBe snapshot.lastTabId
                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                snapshot.tabs.map { it.resourcesLoaded } shouldContainExactly listOf(false, true)
                repositories.openedPaths shouldContainExactly listOf(ALPHA, BETA)
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA)
                settings.stored.activeTabIndex shouldBe 1
            }
        }

        `when`("같은 경로를 두 번 열면") {
            then("탭을 새로 만든다 — 같은 저장소를 두 탭으로 여는 것은 사용자의 선택이다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)

                useCase.open(ALPHA)
                useCase.open(BETA)
                val snapshot = useCase.open(ALPHA)

                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                snapshot.tabs.map { it.id }.distinct().size shouldBe 3
                snapshot.activeTabId shouldBe snapshot.lastTabId
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                settings.stored.activeTabIndex shouldBe 2
            }
        }
    }

    given("세 탭이 열린 세션") {
        `when`("가장 최근에 쓰지 않은 탭으로 전환하면") {
            then("탭 상태는 남고 이전 활성 탭의 자원만 회수한다") {
                val repositories = SessionGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                val alpha = useCase.open(ALPHA).lastTabId
                useCase.open(BETA)
                useCase.open(GAMMA)

                val snapshot = useCase.activate(alpha)

                snapshot.activeTabId shouldBe alpha
                snapshot.tabs.map { it.resourcesLoaded } shouldContainExactly listOf(true, false, false)
                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA, GAMMA)
            }
        }
    }

    given("세션 상한만큼 탭이 열린 세션") {
        `when`("상한을 넘겨 새 탭을 열면") {
            then("가장 오래 쓰지 않은 탭의 자원만 회수하고 탭과 설정 목록은 유지한다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val opened = (0..MAX_LOADED_SESSIONS).map(::repositoryAt)

                opened.take(MAX_LOADED_SESSIONS).forEach { useCase.open(it) }
                repositories.releasedKeys.shouldBeEmpty()

                val snapshot = useCase.open(opened.last())

                repositories.releasedKeys shouldContainExactly listOf(keyOf(opened.first()))
                snapshot.tabs.map { it.path } shouldContainExactly opened
                settings.stored.openTabs shouldContainExactly opened
            }
        }

        `when`("상한 안에서 가장 오래된 탭을 다시 쓴 뒤 새 탭을 열면") {
            then("다시 쓴 탭이 아니라 그다음으로 오래된 탭을 회수한다") {
                val repositories = SessionGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                val opened = (0..MAX_LOADED_SESSIONS).map(::repositoryAt)

                val first = useCase.open(opened.first()).lastTabId
                opened.drop(1).take(MAX_LOADED_SESSIONS - 1).forEach { useCase.open(it) }
                useCase.activate(first)
                useCase.open(opened.last())

                repositories.releasedKeys shouldContainExactly listOf(keyOf(opened[1]))
            }
        }
    }

    given("같은 저장소를 다른 경로 표기로 연 탭") {
        `when`("별칭 탭을 닫으면") {
            then("같은 세션을 쓰는 탭이 남아 있으므로 핸들을 회수하지 않는다") {
                val repositories = canonicalizingGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                val original = useCase.open(ALPHA).lastTabId
                val alias = useCase.open(ALPHA_ALIAS).lastTabId

                val snapshot = useCase.close(original)

                repositories.releasedKeys.shouldBeEmpty()
                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA))
                snapshot.activeTabId shouldBe alias
                snapshot.tabs.single().resourcesLoaded shouldBe true
            }
        }

        `when`("상한 직전에서 별칭 탭을 더 열면") {
            then("세션 키가 같으므로 상한 계산에 한 번만 들고 아무 것도 회수하지 않는다") {
                val repositories = canonicalizingGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                useCase.open(ALPHA)
                (1 until MAX_LOADED_SESSIONS).forEach { index -> useCase.open(repositoryAt(index)) }

                val snapshot = useCase.open(ALPHA_ALIAS)

                repositories.releasedKeys.shouldBeEmpty()
                snapshot.tabs.size shouldBe MAX_LOADED_SESSIONS + 1
            }
        }
    }

    given("활성 탭과 비활성 탭이 있는 세션") {
        `when`("비활성 탭을 닫으면") {
            then("그 탭의 자원만 해제하고 활성 저장소는 건드리지 않는다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                useCase.open(BETA)

                val snapshot = useCase.close(alpha)

                repositories.releasedKeys shouldContainExactly listOf(keyOf(ALPHA))
                repositories.closeCount shouldBe 0
                snapshot.tabs.map { it.path } shouldContainExactly listOf(BETA)
                snapshot.activeTabId shouldBe snapshot.lastTabId
                settings.stored.openTabs shouldContainExactly listOf(BETA)
            }
        }

        `when`("활성 탭을 닫으면") {
            then("JGit 자원을 즉시 해제하고 다음 탭을 활성화한다") {
                val repositories = SessionGateway()
                val useCase = RepositorySessionUseCase(repositories, SessionSettingsGateway())
                useCase.open(ALPHA)
                val beta = useCase.open(BETA).lastTabId

                val snapshot = useCase.close(beta)

                repositories.releasedKeys shouldContainExactly listOf(keyOf(BETA))
                repositories.openedPaths shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                snapshot.tabs.single().path shouldBe ALPHA
                snapshot.tabs.single().resourcesLoaded shouldBe true
            }
        }
    }

    given("저장된 탭과 범위 밖 활성 인덱스") {
        `when`("세션을 복원하면") {
            then("인덱스를 0으로 클램프하고 사라진 경로도 오류 탭으로 남긴다") {
                val repositories = SessionGateway(failingPaths = setOf(MISSING))
                val settings = SessionSettingsGateway(openTabs = listOf(MISSING, BETA), activeTabIndex = 99)
                val useCase = RepositorySessionUseCase(repositories, settings)

                val snapshot = useCase.restore()

                snapshot.activeTabId shouldBe snapshot.tabIdAt(0)
                snapshot.tabs.map { it.availability } shouldContainExactly listOf(
                    TabAvailability.MissingPath,
                    TabAvailability.Available,
                )
                snapshot.tabs.first().resourcesLoaded shouldBe false
                repositories.openedPaths shouldContainExactly listOf(MISSING)
                settings.stored.openTabs shouldContainExactly listOf(MISSING, BETA)
                settings.stored.activeTabIndex shouldBe 0
            }
        }
    }

    given("빈 저장 탭 목록") {
        `when`("세션을 복원하면") {
            then("저장소를 열지 않고 빈 세션으로 남는다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)

                val snapshot = useCase.restore()

                snapshot.tabs.shouldBeEmpty()
                snapshot.activeTabId shouldBe null
                repositories.openedPaths.shouldBeEmpty()
                settings.stored.openTabs.shouldBeEmpty()
            }
        }
    }

    given("중복 경로가 저장된 탭 목록") {
        `when`("세션을 복원하면") {
            then("중복을 접지 않고 그대로 복원한다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway(openTabs = listOf(ALPHA, BETA, ALPHA), activeTabIndex = 2)
                val useCase = RepositorySessionUseCase(repositories, settings)

                val snapshot = useCase.restore()

                snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                snapshot.tabs.map { it.id }.distinct().size shouldBe 3
                snapshot.activeTabId shouldBe snapshot.tabIdAt(2)
            }
        }

        `when`("복원 직후 저장된 값을 다시 읽으면") {
            then("탭 목록과 활성 인덱스가 왕복해도 그대로다 — 복원이 사용자 목록을 줄이지 않는다") {
                val settings = SessionSettingsGateway(openTabs = listOf(ALPHA, BETA, ALPHA), activeTabIndex = 2)
                val useCase = RepositorySessionUseCase(SessionGateway(), settings)

                useCase.restore()

                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                settings.stored.activeTabIndex shouldBe 2

                val reopened = RepositorySessionUseCase(SessionGateway(), settings).restore()

                reopened.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA, ALPHA)
                reopened.activeTabId shouldBe reopened.tabIdAt(2)
            }
        }
    }

    given("설정 저장이 실패하는 세션") {
        `when`("새 탭을 열면") {
            then("탭 장부를 전이 이전으로 되돌리고 실패를 그대로 올린다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway(failUpdates = false)
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.open(BETA) }

                // 장부가 모르는 핸들이 남으면 아무도 회수하지 않는다 — 되돌리기는 집합까지 되돌린다.
                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA))
                settings.failUpdates = false
                val recovered = useCase.activate(alpha)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA)
                recovered.activeTabId shouldBe alpha
                settings.stored.openTabs shouldContainExactly listOf(ALPHA)
            }
        }

        `when`("활성 탭을 닫으면") {
            then("닫기 전 탭 목록과 활성 핸들을 되돌린다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                useCase.open(ALPHA)
                val beta = useCase.open(BETA).lastTabId
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.close(beta) }

                settings.failUpdates = false
                val recovered = useCase.activate(beta)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                repositories.openedPaths.last() shouldBe BETA
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA)
            }
        }
    }

    given("설정 저장이 실패하는 활성 전환") {
        `when`("상한만큼 열린 세션에서 전환이 실패하면") {
            then("유지 목록의 LRU 순서까지 되돌려 다음 회수 대상이 바뀌지 않는다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val opened = (0..MAX_LOADED_SESSIONS).map(::repositoryAt)
                val first = useCase.open(opened.first()).lastTabId
                opened.drop(1).take(MAX_LOADED_SESSIONS - 1).forEach { useCase.open(it) }
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.activate(first) }

                settings.failUpdates = false
                useCase.open(opened.last())

                repositories.releasedKeys shouldContainExactly listOf(keyOf(opened.first()))
                repositories.openSessions shouldContainExactly opened.drop(1).map(::keyOf)
            }
        }

        `when`("비활성 탭 닫기가 실패하면") {
            then("이미 해제한 핸들을 다시 열어 장부와 실제 세션을 맞춘다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                useCase.open(BETA)
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.close(alpha) }

                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA), keyOf(BETA))
                settings.failUpdates = false
                val recovered = useCase.activate(alpha)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA)
            }
        }

        `when`("복원이 실패하면") {
            then("복원이 전부 닫은 세션을 되돌려 연다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                useCase.open(BETA)
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.restore() }

                repositories.closeCount shouldBe 1
                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA), keyOf(BETA))
                settings.failUpdates = false
                val recovered = useCase.activate(alpha)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
            }
        }

        `when`("되돌리기가 일부 세션만 되살리면") {
            then("되살리지 못한 탭을 열려 있다고 표시하지 않는다 — 탭 자체는 남긴다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                val beta = useCase.open(BETA).lastTabId
                repositories.unrestorableKeys = setOf(keyOf(ALPHA))
                settings.failUpdates = true

                shouldThrow<UndineException.GitOperationFailed> { useCase.activate(alpha) }

                settings.failUpdates = false
                val recovered = useCase.activate(beta)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                recovered.tabs.first().availability shouldBe TabAvailability.MissingPath
                recovered.tabs.first().resourcesLoaded shouldBe false
                repositories.openSessions shouldContainExactly listOf(keyOf(BETA))
            }
        }

        `when`("되돌리기가 일부만 바꾼 뒤 예외로 끊기면") {
            then("복원점 목록을 열려 있다고 믿지 않고 전부 닫아 표시와 실제 핸들을 맞춘다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                val beta = useCase.open(BETA).lastTabId
                repositories.restoreFailure = { IOException("되돌리기 중단") }
                settings.failUpdates = true

                val thrown = shouldThrow<UndineException.GitOperationFailed> { useCase.activate(alpha) }

                thrown.suppressed.map { it.message } shouldContainExactly listOf("되돌리기 중단")
                repositories.openSessions.shouldBeEmpty()
                repositories.closeCount shouldBe 1

                settings.failUpdates = false
                val recovered = useCase.activate(beta)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                recovered.tabs.first().availability shouldBe TabAvailability.MissingPath
                recovered.tabs.first().resourcesLoaded shouldBe false
                repositories.openSessions shouldContainExactly listOf(keyOf(BETA))
            }
        }

        `when`("도메인 예외가 아닌 IOException 으로 실패하면") {
            then("같은 보상을 거치고 원인을 그대로 올린다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                settings.updateFailure = { IOException("디스크 없음") }
                settings.failUpdates = true

                shouldThrow<IOException> { useCase.open(BETA) }

                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA))
                settings.failUpdates = false
                val recovered = useCase.activate(alpha)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA)
                recovered.activeTabId shouldBe alpha
            }
        }

        `when`("저장을 기다리는 사이 전이가 취소되면") {
            then("취소를 전파하면서도 새로 연 핸들을 남기지 않는다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val alpha = useCase.open(ALPHA).lastTabId
                val reachedSave = CompletableDeferred<Unit>()
                settings.updateReached = reachedSave
                settings.updateGate = CompletableDeferred()

                coroutineScope {
                    val transition = launch(Dispatchers.Default) { useCase.open(BETA) }
                    reachedSave.await()
                    transition.cancelAndJoin()
                }

                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA))
                settings.updateGate = null
                val recovered = useCase.activate(alpha)
                recovered.tabs.map { it.path } shouldContainExactly listOf(ALPHA)
                settings.stored.openTabs shouldContainExactly listOf(ALPHA)
            }
        }
    }

    given("한 전이가 설정 저장에서 대기 중인 세션") {
        `when`("그 사이에 다른 전이가 시작되면") {
            then("뒤 전이는 앞 전이가 끝날 때까지 세션을 건드리지 못한다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                val reachedSave = CompletableDeferred<Unit>()
                val save = CompletableDeferred<Unit>()
                settings.updateReached = reachedSave
                settings.updateGate = save

                coroutineScope {
                    val waiting = launch(Dispatchers.Default) { useCase.open(ALPHA) }
                    reachedSave.await()
                    val following = launch(Dispatchers.Default) { useCase.open(BETA) }
                    withContext(Dispatchers.Default) { delay(TRANSITION_OVERLAP_WINDOW_MILLIS) }

                    // 뒤 전이가 임계 구역 밖에서 기다린다 — 아직 어떤 세션도 열지 않았다.
                    repositories.openedPaths shouldContainExactly listOf(ALPHA)

                    save.complete(Unit)
                    waiting.join()
                    following.join()
                }

                repositories.openedPaths shouldContainExactly listOf(ALPHA, BETA)
                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA), keyOf(BETA))
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, BETA)
            }
        }

        `when`("대기하던 앞 전이가 저장 실패로 되돌려지면") {
            then("그 보상이 뒤 전이의 탭과 핸들을 덮지 않는다") {
                val repositories = SessionGateway()
                val settings = SessionSettingsGateway()
                val useCase = RepositorySessionUseCase(repositories, settings)
                useCase.open(ALPHA)
                val reachedSave = CompletableDeferred<Unit>()
                val save = CompletableDeferred<Unit>()
                settings.updateReached = reachedSave
                settings.updateGate = save
                settings.failNextUpdate = true

                coroutineScope {
                    val failing = async(Dispatchers.Default) { runCatching { useCase.open(BETA) } }
                    reachedSave.await()
                    val following = async(Dispatchers.Default) { useCase.open(GAMMA) }
                    withContext(Dispatchers.Default) { delay(TRANSITION_OVERLAP_WINDOW_MILLIS) }
                    save.complete(Unit)

                    failing.await().isFailure shouldBe true
                    val snapshot = following.await()
                    // 되돌려진 BETA 탭은 사라지고 GAMMA 탭은 온전히 남는다.
                    snapshot.tabs.map { it.path } shouldContainExactly listOf(ALPHA, GAMMA)
                    snapshot.activeTabId shouldBe snapshot.lastTabId
                }

                // 장부(설정·스냅샷)와 실제 핸들 집합이 갈라지지 않았다.
                repositories.openSessions shouldContainExactly listOf(keyOf(ALPHA), keyOf(GAMMA))
                settings.stored.openTabs shouldContainExactly listOf(ALPHA, GAMMA)
            }
        }
    }

    given("열지 않은 탭") {
        `when`("활성화를 요청하면") {
            then("상태 위반으로 실패한다") {
                val useCase = RepositorySessionUseCase(SessionGateway(), SessionSettingsGateway())

                shouldThrow<IllegalArgumentException> { useCase.activate(TabId(1)) }
            }
        }
    }
})

/** `./` 표기를 접어 같은 저장소를 같은 세션 키로 주는 Gateway — 실제 Holder 의 `toRealPath` 대역이다. */
private fun canonicalizingGateway(): SessionGateway =
    SessionGateway(canonicalKey = { path -> RepositorySessionKey(path.value.replace("/./", "/")) })

private class SessionGateway(
    private val failingPaths: Set<RepositoryPath> = emptySet(),
    private val canonicalKey: (RepositoryPath) -> RepositorySessionKey = { RepositorySessionKey(it.value) },
) : RepositorySessionGateway, RepositorySessions {

    /**
     * 실제 구현(`GitAccess`)처럼 **전이 전체**를 직렬화한다. 이 대역이 락을 걸지 않으면 교차 전이
     * 회귀 테스트가 통과해도 아무것도 증명하지 못한다.
     */
    private val criticalSection = Mutex()

    override suspend fun <T> transition(block: suspend (RepositorySessions) -> T): T =
        criticalSection.withLock { block(this) }

    val openedPaths = mutableListOf<RepositoryPath>()
    val releasedKeys = mutableListOf<RepositorySessionKey>()

    /**
     * **실제로 열려 있는 핸들 집합**이다. UseCase 의 장부와 이 집합이 어긋난 채 전이가 끝나면
     * 그것이 곧 누수(장부 밖 핸들)거나 헛도는 표시(장부만 열림)다 — 보상 테스트가 여기를 본다.
     */
    val openSessions = LinkedHashSet<RepositorySessionKey>()
    var closeCount = 0
        private set

    /** 되돌리기에서 되살릴 수 없는 세션. 부분 복원을 재현한다. */
    var unrestorableKeys: Set<RepositorySessionKey> = emptySet()

    /** 되돌리기가 **집합을 일부 고친 뒤** 끊기는 상황. 그 시점의 열린 집합은 복원점과도 다르다. */
    var restoreFailure: (() -> Throwable)? = null

    override suspend fun open(path: RepositoryPath): RepositorySessionKey {
        openedPaths += path
        if (path in failingPaths) {
            throw UndineException.InvalidRepositoryPath(
                path.value,
                UndineException.InvalidRepositoryPath.Reason.NOT_FOUND,
            )
        }
        return canonicalKey(path).also { key -> openSessions += key }
    }

    override suspend fun release(key: RepositorySessionKey) {
        releasedKeys += key
        openSessions -= key
    }

    override suspend fun close() {
        closeCount++
        openSessions.clear()
    }

    /** 실제 Holder 처럼 집합을 목록과 일치시키고, 되살릴 수 없는 세션은 결과에서 뺀다. */
    override suspend fun restoreSessions(
        sessions: List<RepositorySessionKey>,
        active: RepositorySessionKey?,
    ): List<RepositorySessionKey> {
        restoreFailure?.let { failure ->
            openSessions.clear()
            openSessions += sessions.take(1)
            throw failure()
        }
        val restored = sessions.filterNot { it in unrestorableKeys }
        openSessions.clear()
        openSessions += restored
        return restored
    }
}

private class SessionSettingsGateway(
    openTabs: List<RepositoryPath> = emptyList(),
    activeTabIndex: Int = 0,
    var failUpdates: Boolean = false,
) : SettingsGateway {

    /**
     * 저장 실패로 흉내 낼 예외. 기본은 번역된 도메인 예외지만, `SettingsGatewayImpl` 은 쓰기 실패를
     * [IOException] 그대로 올리므로 그 경로도 같은 보상 경계에 걸리는지 바꿔 끼워 검증한다.
     */
    var updateFailure: () -> Throwable = {
        UndineException.GitOperationFailed("settings.update", IOException("디스크 없음"))
    }

    /**
     * 저장이 매달리는 지점 — 그 사이의 취소·교차 전이를 재현한다.
     *
     * **한 번만 걸린다.** 매달린 전이 뒤에 오는 전이까지 붙잡으면 "뒤 전이가 기다린 이유" 가
     * 임계 구역인지 이 관문인지 구분할 수 없다.
     */
    var updateGate: CompletableDeferred<Unit>? = null

    /** 다음 저장 **한 번만** 실패시킨다. 동시 전이 중 앞선 쪽만 실패하는 상황을 만든다. */
    var failNextUpdate: Boolean = false

    /** 저장이 [updateGate] 에 닿았음을 알린다. 취소 시점을 결정적으로 만든다. */
    var updateReached: CompletableDeferred<Unit>? = null

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
        updateReached?.complete(Unit)
        updateGate?.let { gate ->
            updateGate = null
            gate.await()
        }
        if (failNextUpdate) {
            failNextUpdate = false
            throw updateFailure()
        }
        if (failUpdates) throw updateFailure()
        stored = transform(stored)
    }
}
