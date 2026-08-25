package dev.undine.application.welcome

import dev.undine.domain.OpenedRepository
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.RemoteRef
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.WindowBounds
import dev.undine.domain.WorkingTreeStatus
import kotlinx.coroutines.CompletableDeferred

internal val TEST_WINDOW = WindowBounds(width = 1280, height = 800, maximized = false)

// vararg 로 받을 수 없다 — RepositoryPath 는 value class 라 배열 파라미터 타입으로 금지된다.
internal fun settingsWith(recent: List<RepositoryPath> = emptyList()) = Settings(
    recentRepositories = recent,
    theme = ThemeMode.SYSTEM,
    window = TEST_WINDOW,
)

/**
 * 메모리에 사는 설정 저장소.
 *
 * 실제 `SettingsGatewayImpl` 이 저장 시점에 하는 중복 제거·20개 절단은 **여기서 흉내 내지 않는다** —
 * UseCase 가 그 책임을 떠안았는지(=자기가 절단하는지)를 테스트가 구분할 수 있어야 한다.
 */
internal class FakeSettingsGateway(initial: Settings = settingsWith()) : SettingsGateway {
    var stored: Settings = initial
        private set
    var saveCount: Int = 0
        private set

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
        saveCount++
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        val updated = transform(stored)
        if (updated != stored) save(updated)
    }
}

/** 열기 결과를 경로별로 정해 두는 저장소 게이트웨이. 등록되지 않은 경로는 NOT_FOUND 로 실패한다. */
internal class FakeRepositoryGateway(
    private val failures: Map<RepositoryPath, UndineException> = emptyMap(),
) : RepositoryGateway {
    val openedPaths: MutableList<RepositoryPath> = mutableListOf()

    override suspend fun open(path: RepositoryPath): OpenedRepository {
        failures[path]?.let { throw it }
        openedPaths += path
        return OpenedRepository(state = RepositoryState.NORMAL, currentBranch = RefName("refs/heads/main"))
    }

    override suspend fun status(): WorkingTreeStatus = error("이 테스트는 status 를 쓰지 않습니다")

    override suspend fun close() = Unit
}

/**
 * clone 동작을 시나리오로 지정하는 원격 게이트웨이.
 *
 * @param progressUpdates clone 중 흘려보낼 진행률.
 * @param failure 진행률을 다 흘린 뒤 던질 예외. null 이면 성공한다.
 * @param suspendUntil 값이 있으면 진행률을 흘린 뒤 이 신호를 기다린다 — 취소 시나리오용이다.
 * @param beforeFailure [failure] 를 던지기 직전 실행한다 — clone 중 대상 경로가 바뀌는 상황을 재현한다.
 */
internal class FakeRemoteGateway(
    private val progressUpdates: List<Progress> = emptyList(),
    private val failure: UndineException? = null,
    private val suspendUntil: CompletableDeferred<Unit>? = null,
    private val onClone: (RepositoryPath) -> Unit = {},
    private val beforeFailure: () -> Unit = {},
    /** listRemotes 가 돌려줄 이름. 원격 없음도 정상 시나리오라 기본값은 origin 하나다. */
    private val remoteNames: List<String> = listOf("origin"),
) : RemoteGateway {
    var cloneCount: Int = 0
        private set

    override suspend fun listRemotes(): List<String> = remoteNames

    override suspend fun clone(url: String, into: RepositoryPath, onProgress: (Progress) -> Unit) {
        cloneCount++
        progressUpdates.forEach(onProgress)
        suspendUntil?.await()
        failure?.let {
            beforeFailure()
            throw it
        }
        onClone(into)
    }

    override suspend fun fetch(remote: String, onProgress: (Progress) -> Unit): List<RemoteRef> =
        error("이 테스트는 fetch 를 쓰지 않습니다")

    override suspend fun pull(remote: String, onProgress: (Progress) -> Unit) =
        error("이 테스트는 pull 을 쓰지 않습니다")

    override suspend fun push(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushResult =
        error("이 테스트는 push 를 쓰지 않습니다")
}
