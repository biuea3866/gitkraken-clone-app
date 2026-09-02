package dev.undine.presentation

import dev.undine.application.session.RepositorySessionUseCase
import dev.undine.application.session.TabId
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionGateway
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.RepositorySessions
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.WindowBounds
import dev.undine.presentation.shell.ActiveRepository
import dev.undine.presentation.tabs.TabKeyboardAction
import dev.undine.presentation.tabs.TabKeyboardResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val ALPHA = RepositoryPath("/repositories/alpha")
private val BETA = RepositoryPath("/repositories/beta")

/** 같은 저장소의 다른 표기. 홀더가 정규화해 [ALPHA] 와 같은 세션 키를 준다. */
private val ALPHA_ALIAS = RepositoryPath("/repositories/./alpha")

/**
 * baseline(브랜치·HEAD)이 같은 clone 둘. **경로가 다르므로 세션 키도 다르다** — 범위가 갈리는
 * 기준이 baseline 이 아니라 저장소 정체성이라는 것을 이 쌍이 드러낸다.
 */
private val CLONE_A = RepositoryPath("/repositories/clone-a")
private val CLONE_B = RepositoryPath("/repositories/clone-b")

/**
 * 뒤 전이가 임계 구역 밖에서 기다리는 동안 앞 전이를 붙잡아 두는 시간.
 * 겹침이 실제로 일어나야 반영 순서를 검증할 수 있다.
 */
private const val TRANSITION_OVERLAP_WINDOW_MILLIS = 200L

/** 화면을 그리는 스레드 대역 — 프로덕션에서는 Compose 스코프의 디스패처다. */
private const val UI_THREAD = "undine-ui"

/** Git I/O 직렬화 경계 대역 — 프로덕션에서는 `Dispatchers.IO` 위의 `GitAccess` 임계 구역이다. */
private const val TRANSITION_THREAD = "undine-transition"

/** 되돌리기 범위 대역. 인스턴스 동일성만 보므로 내용이 없다 — 범위가 바뀌면 인스턴스가 바뀐다. */
private class FakeUndoScope

/**
 * 탭 세션 배선(UND-81)의 계약.
 *
 * 화면을 그리지 않고 **배선 그 자체**를 본다 — 탭 전이가 셸 선택과 되돌리기 범위를 같은 저장소로
 * 옮기는가, 범위가 탭 수명(저장소 정체성)을 따르는가, 실패가 사용자의 것을 지우지 않는가.
 */
class RepositorySessionDriverSpec : BehaviorSpec({

    given("탭이 없는 배선") {
        `when`("저장소를 탭으로 열면") {
            then("셸 선택이 그 저장소를 가리킨다") {
                val wiring = driverUnderTest()

                wiring.driver.open(ALPHA)

                wiring.selected shouldBe ActiveRepository.Operable(ALPHA)
                wiring.driver.tabs.tabs.map { it.path } shouldContainExactly listOf(ALPHA)
            }
        }
    }

    given("서로 다른 저장소를 연 탭 둘") {
        `when`("탭을 오가면") {
            then("셸 선택과 되돌리기 범위가 함께 그 탭의 저장소로 옮겨간다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val alphaScope = wiring.driver.activeUndoScope
                val alphaTab = wiring.driver.tabs.tabs.first().id
                wiring.driver.open(BETA)
                val betaScope = wiring.driver.activeUndoScope

                betaScope shouldNotBe alphaScope
                wiring.selected shouldBe ActiveRepository.Operable(BETA)

                wiring.driver.activate(alphaTab)

                wiring.selected shouldBe ActiveRepository.Operable(ALPHA)
                // 전환은 범위를 **버리지 않는다** — 돌아오면 그 저장소의 이력이 그대로 있다.
                wiring.driver.activeUndoScope shouldBe alphaScope
            }
        }

        `when`("한쪽 탭을 닫으면") {
            then("닫힌 저장소의 범위만 사라지고 남은 탭의 범위는 그대로다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val alphaTab = wiring.driver.tabs.tabs.first().id
                val alphaScope = wiring.driver.activeUndoScope
                wiring.driver.open(BETA)
                val betaScope = wiring.driver.activeUndoScope

                wiring.driver.close(wiring.driver.tabs.tabs.last().id)

                wiring.driver.activeUndoScope shouldBe alphaScope
                wiring.driver.tabs.tabs.map { it.id } shouldContainExactly listOf(alphaTab)

                // 닫았던 저장소를 다시 열면 새 범위다 — 버린 이력은 되살아나지 않는다.
                wiring.driver.open(BETA)
                wiring.driver.activeUndoScope shouldNotBe betaScope
            }
        }
    }

    given("같은 저장소를 연 중복 탭 둘") {
        `when`("두 탭을 오가면") {
            then("하나의 되돌리기 범위를 공유한다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val first = wiring.driver.tabs.tabs.first().id
                val shared = wiring.driver.activeUndoScope
                // 별칭 경로로 열어도 홀더가 정규화한 키가 같으므로 같은 범위다.
                wiring.driver.open(ALPHA_ALIAS)

                wiring.driver.activeUndoScope shouldBe shared

                wiring.driver.activate(first)
                wiring.driver.activeUndoScope shouldBe shared
            }
        }

        `when`("탭 하나만 닫으면") {
            then("남은 참조 탭이 있으므로 범위를 보존하고, 마지막 참조를 닫을 때 버린다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val first = wiring.driver.tabs.tabs.first().id
                val shared = wiring.driver.activeUndoScope
                wiring.driver.open(ALPHA_ALIAS)
                val second = wiring.driver.tabs.tabs.last().id

                wiring.driver.close(second)

                wiring.driver.activeUndoScope shouldBe shared

                wiring.driver.close(first)
                wiring.driver.open(ALPHA)
                wiring.driver.activeUndoScope shouldNotBe shared
            }
        }
    }

    given("baseline 이 같은 clone 둘") {
        `when`("두 저장소를 탭으로 열면") {
            then("세션 키가 다르므로 범위가 절대 섞이지 않는다") {
                val wiring = driverUnderTest()
                wiring.driver.open(CLONE_A)
                val cloneA = wiring.driver.activeUndoScope
                wiring.driver.open(CLONE_B)

                wiring.driver.activeUndoScope shouldNotBe cloneA
            }
        }
    }

    given("탭이 열려 있는 배선") {
        `when`("전이가 실패하면") {
            then("탭도 그 저장소의 되돌리기 범위도 지우지 않는다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val tab = wiring.driver.tabs.tabs.first().id
                val scope = wiring.driver.activeUndoScope
                wiring.settings.failUpdates = true

                shouldThrow<UndineException> { wiring.driver.activate(tab) }

                wiring.driver.tabs.tabs.map { it.id } shouldContainExactly listOf(tab)
                wiring.driver.activeUndoScope shouldBe scope
                wiring.selected shouldBe ActiveRepository.Operable(ALPHA)
            }
        }

        `when`("키보드로 다음 탭을 고르면") {
            then("마우스와 같은 전이를 타 활성 저장소가 바뀐다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                wiring.driver.open(BETA)

                // 탭 막대(`RepositoryTabs`)의 Ctrl+Tab 경로가 하는 그대로다 — 홀더가 고른 탭을
                // 클릭과 **같은** 전이로 활성화한다. 키보드만 다른 길로 가면 그 길에서만 나는
                // 불일치를 아무도 보지 못한다.
                val moved = wiring.driver.tabs.handleKeyboard(TabKeyboardAction.Next)
                moved.shouldBeInstanceOf<TabKeyboardResult.Activated>()
                wiring.driver.activate(moved.tabId)

                wiring.selected shouldBe ActiveRepository.Operable(ALPHA)
            }
        }

        `when`("마지막 탭을 닫으면") {
            then("셸 선택이 비고 되돌리기 범위도 저장소에 매이지 않는다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val scope = wiring.driver.activeUndoScope

                wiring.driver.closeActive()

                wiring.selected shouldBe ActiveRepository.None
                wiring.driver.tabs.tabs.shouldBeEmpty()
                wiring.driver.activeUndoScope shouldNotBe scope
            }
        }
    }

    given("설정에 저장된 탭 목록") {
        `when`("복원하면") {
            then("저장된 탭이 그대로 열리고 활성 탭이 셸 선택이 된다") {
                val wiring = driverUnderTest(openTabs = listOf(ALPHA, BETA), activeTabIndex = 1)

                wiring.driver.restore()

                wiring.driver.tabs.tabs.map { it.path } shouldContainExactly listOf(ALPHA, BETA)
                wiring.selected shouldBe ActiveRepository.Operable(BETA)
            }
        }
    }

    given("겹쳐 들어오는 서로 다른 저장소의 전이") {
        `when`("앞 전이가 임계 구역에 머무는 사이 다른 탭이 활성화되면") {
            then("마지막 전이의 저장소로 탭·셸 선택·되돌리기 범위가 함께 간다") {
                val wiring = driverUnderTest()
                wiring.driver.open(ALPHA)
                val alphaTab = wiring.driver.tabs.tabs.first().id
                wiring.driver.open(BETA)
                val betaTab = wiring.driver.tabs.tabs.last().id
                val betaScope = wiring.driver.activeUndoScope
                wiring.selections.clear()

                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                wiring.settings.holdNextUpdate(reached, release)

                coroutineScope {
                    val first = launch(Dispatchers.Default) { wiring.driver.activate(alphaTab) }
                    reached.await()
                    val second = launch(Dispatchers.Default) { wiring.driver.activate(betaTab) }
                    delay(TRANSITION_OVERLAP_WINDOW_MILLIS)
                    // 뒤 전이는 아직 임계 구역 밖이다 — 어떤 반영도 일어나지 않았다.
                    wiring.selections.shouldBeEmpty()
                    release.complete(Unit)
                    first.join()
                    second.join()
                }

                // 반영 순서가 완료 순서와 같다. 뒤집히면 나중에 고른 BETA 위에 ALPHA 가 덮인다.
                wiring.selections shouldContainExactly
                    listOf(ActiveRepository.Operable(ALPHA), ActiveRepository.Operable(BETA))
                wiring.driver.tabs.activeTabId shouldBe betaTab
                wiring.selected shouldBe ActiveRepository.Operable(BETA)
                wiring.driver.activeUndoScope shouldBe betaScope
            }
        }
    }

    given("Git I/O 경계에서 도는 전이") {
        `when`("결과를 표시 상태로 옮기면") {
            then("반영은 전이를 띄운 디스패처에서, 임계 구역을 벗어나기 전에 끝난다") {
                withThreads { uiDispatcher, transitionDispatcher ->
                    val wiring = driverUnderTest(transitionOn = transitionDispatcher)

                    withContext(uiDispatcher) { wiring.driver.open(ALPHA) }

                    // Compose 상태를 Git I/O 스레드에서 바꾸지 않는다 (결정 G41).
                    wiring.reflectionThreads shouldContainExactly listOf(UI_THREAD)
                    // 그러면서도 반영은 락 안에서 끝난다 — 스냅샷만 만들고 놓으면 순서가 뒤집힌다.
                    wiring.sessionGateway.events shouldContainExactly listOf("반영", "임계 구역 이탈")
                }
            }
        }
    }

    given("최근 저장소 열기·clone 이 띄우는 세션 전이") {
        `when`("전이가 실패하면") {
            then("도메인 실패도 입출력 실패도 전역 안내에 올라간다") {
                // 실패는 **부를 때마다 새로 만든다.** 보상도 같은 Gateway 를 타므로 같은 인스턴스를
                // 돌려주면 자기 자신을 suppressed 로 매다는 상황이 되는데, 그건 실제 Gateway 가
                // 만들 수 없는 모양이라 검증 대상이 아니다.
                listOf<Pair<() -> Throwable, String>>(
                    { UndineException.GitOperationFailed("settings.update") } to "GitOperationFailed",
                    { IOException("디스크 없음") } to "IOException",
                ).forEach { (failure, expectedKind) ->
                    val wiring = driverUnderTest()
                    val errors = AppErrorState()
                    wiring.settings.updateFailure = failure
                    wiring.settings.failUpdates = true

                    coroutineScope { reportingFailure(errors) { wiring.driver.open(ALPHA) } }

                    errors.failure?.kind shouldBe expectedKind
                }
            }
        }

        `when`("화면이 사라져 전이가 취소되면") {
            then("사용자에게 알릴 실패가 아니므로 안내하지 않는다") {
                val wiring = driverUnderTest()
                val errors = AppErrorState()
                val reached = CompletableDeferred<Unit>()
                wiring.settings.holdNextUpdate(reached, CompletableDeferred())

                coroutineScope {
                    val job = launch(Dispatchers.Default) {
                        reportingFailure(errors) { wiring.driver.open(ALPHA) }
                    }
                    reached.await()
                    job.cancelAndJoin()
                }

                errors.failure shouldBe null
            }
        }
    }

    given("경로를 잃은 탭") {
        `when`("활성 탭의 저장소가 사라져 세션 키가 없어지면") {
            then("조작 대상은 비우되 탭이 가리키는 저장소는 남긴다") {
                val fixture = driverUnderTest()
                fixture.driver.open(ALPHA)
                val alphaTab = fixture.driver.tabs.tabs.first().id
                fixture.driver.open(BETA)

                // ALPHA 가 지워진 뒤 그 탭으로 돌아간다.
                fixture.sessionGateway.missingPaths += ALPHA
                fixture.driver.activate(alphaTab)

                // 조작 대상으로 넘기면 화면은 ALPHA 를 가리키는데 핸들은 BETA 다 — 조작이 남의
                // 저장소로 간다. 그렇다고 아무것도 안 넘기면 셸이 사라져 탭 막대까지 함께 사라진다.
                // 그래서 **다른 타입**으로 넘긴다 (UND-83).
                fixture.selections.last() shouldBe ActiveRepository.Unavailable(ALPHA)
                fixture.selections.last().referencedPath shouldBe ALPHA
            }

            then("직전 저장소의 Undo 이력을 그 탭의 것으로 되살리지 않는다") {
                val fixture = driverUnderTest()
                fixture.driver.open(ALPHA)
                val alphaScope = fixture.driver.activeUndoScope
                val alphaTab = fixture.driver.tabs.tabs.first().id
                fixture.driver.open(BETA)

                fixture.sessionGateway.missingPaths += ALPHA
                fixture.driver.activate(alphaTab)

                fixture.driver.activeUndoScope shouldNotBe alphaScope
            }
        }

        `when`("경로를 잃었다가 다시 열리면") {
            then("그 저장소의 이력이 그대로 이어진다 — 일시적 부재로 지우지 않는다") {
                val fixture = driverUnderTest()
                fixture.driver.open(ALPHA)
                val alphaScope = fixture.driver.activeUndoScope
                val alphaTab = fixture.driver.tabs.tabs.first().id
                fixture.driver.open(BETA)

                fixture.sessionGateway.missingPaths += ALPHA
                fixture.driver.activate(alphaTab)
                fixture.sessionGateway.missingPaths -= ALPHA
                fixture.driver.activate(alphaTab)

                fixture.driver.activeUndoScope shouldBe alphaScope
            }
        }
    }

    given("배선 장부의 보존") {
        // 이 두 건은 **배선의 장부만** 본다. `App.kt` 의 컨텍스트 조회(`currentRepository`·
        // `listBranches`·`listTags`·`listRemotes`)가 실패했을 때의 보존은 여기서 검증되지 않는다 —
        // 그 경로는 이 배선을 부르지 않으므로 App 쪽 테스트가 필요하다 (UND-83).
        `when`("전이 없이 탭을 오가면") {
            then("탭 목록·활성 탭·그 저장소의 Undo 이력이 그대로다") {
                val fixture = driverUnderTest()
                fixture.driver.open(ALPHA)
                val alphaScope = fixture.driver.activeUndoScope
                val alphaTab = fixture.driver.tabs.tabs.first().id
                fixture.driver.open(BETA)
                val betaTab = fixture.driver.tabs.tabs.last().id

                // 조회 실패는 배선 밖(App 의 LaunchedEffect)에서 일어나고 전이를 부르지 않는다.
                // 그래서 이 배선의 장부는 **아무것도 잃지 않아야** 한다.
                fixture.driver.activate(alphaTab)

                fixture.driver.tabs.tabs.map { it.id } shouldContainExactly listOf(alphaTab, betaTab)
                fixture.driver.tabs.activeTabId shouldBe alphaTab
                fixture.driver.activeUndoScope shouldBe alphaScope
            }
        }

        `when`("설정 저장이 실패해 전이가 되돌려지면") {
            then("되돌린 뒤에도 이전 활성 탭의 이력이 그대로다") {
                val fixture = driverUnderTest()
                fixture.driver.open(ALPHA)
                val alphaScope = fixture.driver.activeUndoScope
                val alphaTab = fixture.driver.tabs.tabs.first().id

                fixture.settings.failUpdates = true
                shouldThrow<UndineException> { fixture.driver.open(BETA) }

                fixture.driver.tabs.activeTabId shouldBe alphaTab
                fixture.driver.activeUndoScope shouldBe alphaScope
            }
        }
    }

    given("전이 직렬화의 소유자") {
        `when`("presentation 과 application 세션 코드를 훑으면") {
            then("소비자 쪽 Mutex 가 없다 — 직렬화는 홀더가 소유한다 (결정 A-N1)") {
                val offenders = consumerSources()
                    .filter { source -> source.readText().contains("Mutex") }
                    .map(File::getName)

                offenders.shouldBeEmpty()
            }
        }
    }
})

private class DriverUnderTest(
    val driver: RepositorySessionDriver<FakeUndoScope>,
    val settings: DriverSettingsGateway,
    val sessionGateway: DriverSessionGateway,
    /** 반영이 일어난 순서대로의 활성 저장소. 순서가 곧 화면이 본 순서다. */
    val selections: MutableList<ActiveRepository>,
    /** 반영이 일어난 스레드 이름 — Compose 상태를 어디서 바꿨는지가 여기 남는다. */
    val reflectionThreads: MutableList<String>,
) {
    val selected: ActiveRepository? get() = selections.lastOrNull()
}

private fun driverUnderTest(
    openTabs: List<RepositoryPath> = emptyList(),
    activeTabIndex: Int = 0,
    transitionOn: CoroutineDispatcher? = null,
): DriverUnderTest {
    val settings = DriverSettingsGateway(openTabs, activeTabIndex)
    val sessionGateway = DriverSessionGateway(transitionOn)
    val selections = Collections.synchronizedList(mutableListOf<ActiveRepository>())
    val reflectionThreads = Collections.synchronizedList(mutableListOf<String>())
    val driver = RepositorySessionDriver(
        sessions = RepositorySessionUseCase(sessionGateway, settings),
        createUndoScope = ::FakeUndoScope,
        onActiveRepository = { active ->
            sessionGateway.events += "반영"
            // 코루틴 디버그 이름(` @coroutine#N`)을 떼고 스레드만 남긴다.
            reflectionThreads += Thread.currentThread().name.substringBefore(" @")
            selections += active
        },
    )
    return DriverUnderTest(driver, settings, sessionGateway, selections, reflectionThreads)
}

/**
 * 화면 스레드와 Git I/O 스레드를 갈라 준다.
 *
 * 이름 붙인 단일 스레드 둘로 도는 것이 요점이다 — 반영이 어느 쪽에서 일어났는지 스레드 이름으로
 * 단정할 수 있어야 "IO 스레드에서 Compose 를 바꾸지 않는다" 를 검증할 수 있다.
 */
private suspend fun withThreads(
    block: suspend (uiDispatcher: CoroutineDispatcher, transitionDispatcher: CoroutineDispatcher) -> Unit,
) {
    val executors = mutableListOf<ExecutorService>()
    fun named(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
            .also(executors::add)
            .asCoroutineDispatcher()
    try {
        block(named(UI_THREAD), named(TRANSITION_THREAD))
    } finally {
        executors.forEach(ExecutorService::shutdown)
    }
}

/** 소비자(presentation·세션 UseCase) 소스. 실행 위치가 모듈 루트든 저장소 루트든 찾는다. */
private fun consumerSources(): List<File> = listOf(
    "src/main/kotlin/dev/undine/presentation",
    "src/main/kotlin/dev/undine/application/session",
).map { relative ->
    listOf(File(relative), File("app/$relative")).first(File::isDirectory)
}.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }

/**
 * 홀더처럼 **정규화된** 세션 키를 돌려주고 전이 전체를 직렬화한다.
 *
 * [transitionOn] 을 주면 실제 `GitAccess` 처럼 전이 본문을 다른 디스패처(Git I/O 경계)에서 돌린다 —
 * 그 경계에서 Compose 상태가 바뀌는지 보려면 대역도 경계를 옮겨야 한다.
 */
private class DriverSessionGateway(
    private val transitionOn: CoroutineDispatcher? = null,
) : RepositorySessionGateway, RepositorySessions {

    private val criticalSection = Mutex()
    private val opened = LinkedHashSet<RepositorySessionKey>()

    /** 열기가 실패하는 경로 — 저장소가 지워지거나 옮겨진 상황을 흉내 낸다. */
    val missingPaths = mutableSetOf<RepositoryPath>()

    /** 반영과 임계 구역 이탈의 순서. 반영이 락 밖으로 밀리면 여기서 순서가 뒤집힌다. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun <T> transition(block: suspend (RepositorySessions) -> T): T =
        criticalSection.withLock {
            val result = when (transitionOn) {
                null -> block(this)
                else -> withContext(transitionOn) { block(this@DriverSessionGateway) }
            }
            events += "임계 구역 이탈"
            result
        }

    override suspend fun open(path: RepositoryPath): RepositorySessionKey {
        if (path in missingPaths) {
            throw UndineException.InvalidRepositoryPath(
                path.value,
                UndineException.InvalidRepositoryPath.Reason.NOT_FOUND,
            )
        }
        return canonicalKey(path).also { key -> opened += key }
    }

    override suspend fun release(key: RepositorySessionKey) {
        opened -= key
    }

    override suspend fun close() {
        opened.clear()
    }

    override suspend fun restoreSessions(
        sessions: List<RepositorySessionKey>,
        active: RepositorySessionKey?,
    ): List<RepositorySessionKey> {
        opened.clear()
        opened += sessions
        return sessions
    }

    private fun canonicalKey(path: RepositoryPath) = RepositorySessionKey(path.value.replace("/./", "/"))
}

private class DriverSettingsGateway(
    openTabs: List<RepositoryPath>,
    activeTabIndex: Int,
    var failUpdates: Boolean = false,
) : SettingsGateway {

    /** 실패 종류를 고를 수 있어야 도메인 실패와 입출력 실패를 같은 경로로 검증할 수 있다. */
    var updateFailure: () -> Throwable = { UndineException.GitOperationFailed("settings.update") }

    private var reachedGate: CompletableDeferred<Unit>? = null
    private var releaseGate: CompletableDeferred<Unit>? = null

    var stored = Settings(
        recentRepositories = emptyList(),
        theme = ThemeMode.SYSTEM,
        window = WindowBounds(1280, 800, maximized = false),
        openTabs = openTabs,
        activeTabIndex = activeTabIndex,
    )
        private set

    /**
     * **다음 저장 한 번만** 붙잡는다. 관문이 계속 걸려 있으면 뒤 전이도 함께 멈춰 겹침을 만들 수 없다.
     */
    fun holdNextUpdate(reached: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        reachedGate = reached
        releaseGate = release
    }

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        releaseGate?.let { gate ->
            releaseGate = null
            reachedGate?.complete(Unit)
            reachedGate = null
            gate.await()
        }
        if (failUpdates) throw updateFailure()
        stored = transform(stored)
    }
}
