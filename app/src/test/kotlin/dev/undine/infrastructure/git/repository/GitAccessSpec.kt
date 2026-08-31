package dev.undine.infrastructure.git.repository

import dev.undine.application.session.MAX_LOADED_SESSIONS
import dev.undine.application.session.RepositorySessionUseCase
import dev.undine.domain.RepositoryPath
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.WindowBounds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** 겹침을 관측할 수 있을 만큼만 임계 구역에 머무른다. */
private const val CRITICAL_SECTION_MILLIS = 2L

private const val CONCURRENT_CALLERS = 16

/** 시퀀스 임계 구역이 열려 있는 동안 다른 접근이 확실히 대기에 걸릴 만큼만 머무른다. */
private const val SEQUENCE_MILLIS = 50L

/** 대기하던 변경이 **어느 저장소에** 적용됐는지 워킹트리로 관측하기 위한 표식이다. */
private const val PENDING_MARK = "pending.txt"

private fun repositoryAt(directory: File): File {
    initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
    return directory
}

/** 락을 얻은 뒤 실제로 건드린 저장소를 남긴다 — 핸들이 아니라 워킹트리로 판정한다. */
private fun markPending(repository: Repository) {
    File(repository.workTree, PENDING_MARK).writeText("pending\n")
}

private fun File.hasPendingMark(): Boolean = File(this, PENDING_MARK).exists()

/**
 * 실제 핸들을 열되 **무엇을 몇 번 열었는지** 남긴다. 같은 저장소를 두 탭으로 열어도 핸들은
 * 하나여야 하므로, 그 사실을 세션 키가 아니라 **실제 열기 횟수**로 관측한다.
 */
private fun openCountingHolder(opened: MutableList<Path>): RepositoryHolder =
    RepositoryHolder { workTree ->
        opened.add(workTree)
        FileRepository(File(workTree.toFile(), Constants.DOT_GIT))
    }

/** 탭 장부만 필요한 in-memory 설정 저장소다 — 영속화 자체는 `RepositorySessionUseCaseSpec` 이 본다. */
private class SessionSettings : SettingsGateway {

    private var stored = Settings(
        recentRepositories = emptyList(),
        theme = ThemeMode.SYSTEM,
        window = WindowBounds(1280, 800, maximized = false),
    )

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        stored = transform(stored)
    }
}

/** 탭 정책(같은 저장소 공유·LRU 회수)은 이 UseCase 가 소유한다 — 홀더가 아니라 여기서 온다. */
private fun GitAccess.sessionTabs(): RepositorySessionUseCase =
    RepositorySessionUseCase(RepositorySessionGatewayImpl(this), SessionSettings())

/**
 * 락 대기 창을 **결정적으로** 만든다 — 전환이 먼저 실행되고 그 뒤에 대기 작업이 실행된다.
 *
 * 점유자가 [GitAccess.withSessions] 로 락을 쥔 동안 전환들과 대기 작업을 차례로 띄운다. 둘 다
 * `Dispatchers.IO` + `UNDISPATCHED` 로 시작하는 것이 요점이다 — [GitAccess] 안의
 * `withContext(Dispatchers.IO)` 가 같은 디스패처라 그 자리에서 이어 실행되므로, `launch` 가
 * 돌아온 시점에 **이미 락 대기열에 들어가 있다.** `Mutex` 는 선입선출이라 sleep 없이 순서가 고정된다.
 *
 * 대기 작업의 세션 키 캡처는 `withContext` **이전의 동기 구간**이므로, 전환이 아직 락을 얻지 못한
 * 이 시점에 끝난다. 그래서 "캡처 → 전환 → 락 획득" 이 그대로 재현된다.
 */
private suspend fun GitAccess.pendingAcross(
    switchesWhileWaiting: List<suspend () -> Unit>,
    pending: suspend GitAccess.() -> Unit,
) {
    val occupied = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    coroutineScope {
        launch(Dispatchers.Default) {
            withSessions {
                occupied.complete(Unit)
                release.await()
            }
        }
        occupied.await()
        switchesWhileWaiting.forEach { switch ->
            launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { switch() }
        }
        launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { pending() }
        release.complete(Unit)
    }
}

private suspend fun GitAccess.pendingAcross(
    switchWhileWaiting: suspend () -> Unit = { },
    pending: suspend GitAccess.() -> Unit,
) = pendingAcross(listOf(switchWhileWaiting), pending)

class GitAccessSpec : FunSpec({

    test("동시 호출에도 Repository 접근이 겹치지 않는다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(directory.path)) { }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        coroutineScope {
            repeat(CONCURRENT_CALLERS) {
                launch(Dispatchers.Default) {
                    gitAccess.withRepository { repository ->
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        repository.repositoryState
                        Thread.sleep(CRITICAL_SECTION_MILLIS)
                        active.decrementAndGet()
                    }
                }
            }
        }

        maxActive.get() shouldBe 1
        gitAccess.close()
    }

    test("withSequence 가 열려 있는 동안 다른 Git 접근은 구역이 끝난 뒤에 실행된다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gitAccess = GitAccess()
        gitAccess.open(RepositoryPath(directory.path)) { }
        val order = mutableListOf<String>()
        val sequenceStarted = CompletableDeferred<Unit>()

        coroutineScope {
            launch(Dispatchers.Default) {
                gitAccess.withSequence {
                    sequenceStarted.complete(Unit)
                    // 시퀀스 중간 단계를 흉내 낸다 — 이 사이에 다른 접근이 끼어들면 안 된다.
                    Thread.sleep(SEQUENCE_MILLIS)
                    order += "sequence"
                }
            }
            sequenceStarted.await()
            launch(Dispatchers.Default) { gitAccess.withRepository { order += "other" } }
        }

        order shouldBe listOf("sequence", "other")
        gitAccess.close()
    }

    test("열기 전 withRepository 는 StateViolation 을 던진다") {
        val failure = shouldThrow<UndineException.StateViolation> {
            GitAccess().withRepository { it.repositoryState }
        }

        failure.detail shouldBe REPOSITORY_NOT_OPEN
    }

    test("withRepository 는 락 대기 중 활성 저장소가 바뀌어도 시작한 저장소를 바꾼다") {
        val started = repositoryAt(tempdir())
        val switched = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        gitAccess.withSessions { it.open(RepositoryPath(started.path)) }

        gitAccess.pendingAcross(
            switchWhileWaiting = { gitAccess.withSessions { it.open(RepositoryPath(switched.path)) } },
            pending = { withRepository(::markPending) },
        )

        started.hasPendingMark() shouldBe true
        switched.hasPendingMark() shouldBe false
        gitAccess.close()
    }

    test("withSequence 도 락 대기 중 전환된 저장소가 아니라 시작한 저장소를 바꾼다") {
        val started = repositoryAt(tempdir())
        val switched = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        gitAccess.withSessions { it.open(RepositoryPath(started.path)) }

        gitAccess.pendingAcross(
            switchWhileWaiting = { gitAccess.withSessions { it.open(RepositoryPath(switched.path)) } },
            pending = { withSequence(::markPending) },
        )

        started.hasPendingMark() shouldBe true
        switched.hasPendingMark() shouldBe false
        gitAccess.close()
    }

    test("전환이 없으면 대기했던 변경이 그대로 시작한 저장소에 적용된다") {
        val started = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        gitAccess.withSessions { it.open(RepositoryPath(started.path)) }

        gitAccess.pendingAcross(pending = { withRepository(::markPending) })

        started.hasPendingMark() shouldBe true
        gitAccess.close()
    }

    test("시작한 세션이 락 대기 중 닫히면 실행하지 않고 사유를 돌려준다") {
        val started = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        val session = gitAccess.withSessions { it.open(RepositoryPath(started.path)) }
        var outcome: Result<Unit>? = null

        gitAccess.pendingAcross(
            switchWhileWaiting = { gitAccess.withSessions { it.release(session) } },
            pending = { outcome = runCatching { withRepository(::markPending) } },
        )

        val failure = outcome?.exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        failure.detail shouldBe REPOSITORY_SESSION_CLOSED
        // 사유가 화면에 그대로 나갈 수 있어야 한다 — 조용한 성공·빈 결과로 바꾸지 않는다.
        failure.message.shouldContain(REPOSITORY_SESSION_CLOSED)
        started.hasPendingMark() shouldBe false
        gitAccess.close()
    }

    test("withRepository 는 실제 열기 경로로 저장소가 바뀌면 어느 저장소도 바꾸지 않고 거부한다") {
        val started = repositoryAt(tempdir())
        val switched = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        // 사용자가 실제로 저장소를 바꾸는 경로다 — 이 경로는 이전 활성 핸들을 **닫는다**.
        val repositories = RepositoryGatewayImpl(gitAccess)
        repositories.open(RepositoryPath(started.path))
        var outcome: Result<Unit>? = null

        gitAccess.pendingAcross(
            switchWhileWaiting = { repositories.open(RepositoryPath(switched.path)) },
            pending = { outcome = runCatching { withRepository(::markPending) } },
        )

        val failure = outcome?.exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        failure.detail shouldBe REPOSITORY_SESSION_CLOSED
        started.hasPendingMark() shouldBe false
        switched.hasPendingMark() shouldBe false
        gitAccess.close()
    }

    test("withSequence 도 실제 열기 경로로 저장소가 바뀌면 어느 저장소도 바꾸지 않고 거부한다") {
        val started = repositoryAt(tempdir())
        val switched = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        val repositories = RepositoryGatewayImpl(gitAccess)
        repositories.open(RepositoryPath(started.path))
        var outcome: Result<Unit>? = null

        gitAccess.pendingAcross(
            switchWhileWaiting = { repositories.open(RepositoryPath(switched.path)) },
            pending = { outcome = runCatching { withSequence(::markPending) } },
        )

        val failure = outcome?.exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        failure.detail shouldBe REPOSITORY_SESSION_CLOSED
        started.hasPendingMark() shouldBe false
        switched.hasPendingMark() shouldBe false
        gitAccess.close()
    }

    test("두 세션이 열려 있으면 각 작업은 시작 시점의 자기 세션을 바꾼다") {
        val first = repositoryAt(tempdir())
        val second = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        gitAccess.withSessions { it.open(RepositoryPath(first.path)) }
        gitAccess.withSessions { it.open(RepositoryPath(second.path)) }

        // second 를 시작 세션으로 잡은 작업이 대기하는 동안 활성 세션이 first 로 돌아간다.
        gitAccess.pendingAcross(
            switchWhileWaiting = { gitAccess.withSessions { it.open(RepositoryPath(first.path)) } },
            pending = { withRepository(::markPending) },
        )
        gitAccess.withRepository(::markPending)

        second.hasPendingMark() shouldBe true
        first.hasPendingMark() shouldBe true
        gitAccess.close()
    }

    test("같은 저장소를 두 탭으로 열면 핸들 하나를 공유한다") {
        val shared = repositoryAt(tempdir())
        val opened = mutableListOf<Path>()
        val gitAccess = GitAccess(openCountingHolder(opened))
        val tabs = gitAccess.sessionTabs()

        val first = tabs.open(RepositoryPath(shared.path))
        val second = tabs.open(RepositoryPath(shared.path))

        // 탭은 둘이지만 실제로 연 핸들은 하나다 — 정규화된 실경로가 세션 키이기 때문이다.
        first.tabs.size shouldBe 1
        second.tabs.size shouldBe 2
        opened.size shouldBe 1
        gitAccess.close()
    }

    test("같은 저장소를 보는 탭이 남아 있으면 한 탭을 닫아도 시작한 변경이 실행된다") {
        val shared = repositoryAt(tempdir())
        val gitAccess = GitAccess()
        val tabs = gitAccess.sessionTabs()
        val closing = tabs.open(RepositoryPath(shared.path)).tabs.last().id
        tabs.open(RepositoryPath(shared.path))

        gitAccess.pendingAcross(
            switchWhileWaiting = { tabs.close(closing) },
            pending = { withRepository(::markPending) },
        )

        shared.hasPendingMark() shouldBe true
        gitAccess.close()
    }

    test("상한을 넘겨 회수된 시작 세션의 변경은 다른 저장소에 적용되지 않고 거부된다") {
        val started = repositoryAt(tempdir())
        val later = List(MAX_LOADED_SESSIONS) { repositoryAt(tempdir()) }
        val gitAccess = GitAccess()
        val tabs = gitAccess.sessionTabs()
        tabs.open(RepositoryPath(started.path))
        // 상한을 넘길 때까지 새 저장소를 연다 — 가장 오래 쓰지 않은 `started` 세션이 회수된다.
        val opens: List<suspend () -> Unit> = later.map { repository ->
            { tabs.open(RepositoryPath(repository.path)) }
        }
        var outcome: Result<Unit>? = null

        gitAccess.pendingAcross(
            switchesWhileWaiting = opens,
            pending = { outcome = runCatching { withRepository(::markPending) } },
        )

        val failure = outcome?.exceptionOrNull().shouldBeInstanceOf<UndineException.StateViolation>()
        failure.detail shouldBe REPOSITORY_SESSION_CLOSED
        started.hasPendingMark() shouldBe false
        later.any(File::hasPendingMark) shouldBe false
        gitAccess.close()
    }
})
