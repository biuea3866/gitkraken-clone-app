package dev.undine.presentation.welcome

import dev.undine.application.welcome.CloneRepositoryUseCase
import dev.undine.application.welcome.FakeRemoteGateway
import dev.undine.application.welcome.FakeRepositoryGateway
import dev.undine.application.welcome.FakeSettingsGateway
import dev.undine.application.welcome.ForgetRecentRepositoryUseCase
import dev.undine.application.welcome.LoadRecentRepositoriesUseCase
import dev.undine.application.welcome.OpenRepositoryUseCase
import dev.undine.application.welcome.settingsWith
import dev.undine.domain.Progress
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.UndineException.InvalidRepositoryPath.Reason
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.yield
import java.io.File

private val PRESENT = RepositoryPath("/tmp/present")
private val MISSING = RepositoryPath("/tmp/missing")

/** 토큰이 박힌 원격 URL — 이 문자열이 안내 문구에 새는지 감시한다. */
private const val REMOTE_URL = "https://user:secret-token@example.invalid/undine.git"

/**
 * Welcome 화면 상태 홀더.
 *
 * 기본 스코프와 clone 의 IO 디스패처는 [Dispatchers.Unconfined] 다 — 가짜 게이트웨이가 즉시 끝나므로
 * `launch` 가 호출 직후 완료되고 상태를 그 자리에서 검증할 수 있다. 실제 디스패처를 쓰면 단정 시점이
 * 스케줄링에 좌우돼 테스트가 흔들린다. 취소 시나리오만 실제 스레드가 필요해 따로 스코프를 준다.
 */
class WelcomeStateSpec : FunSpec({

    fun stateWith(
        settings: FakeSettingsGateway = FakeSettingsGateway(settingsWith(listOf(PRESENT, MISSING))),
        repositories: FakeRepositoryGateway = FakeRepositoryGateway(),
        remote: FakeRemoteGateway = FakeRemoteGateway(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        onOpened: (RepositoryPath) -> Unit = { },
    ) = WelcomeState(
        actions = WelcomeActions(
            loadRecentRepositories = LoadRecentRepositoriesUseCase(settings),
            openRepository = OpenRepositoryUseCase(repositories, settings),
            cloneRepository = CloneRepositoryUseCase(remote, settings, ioDispatcher = Dispatchers.Unconfined),
            forgetRecentRepository = ForgetRecentRepositoryUseCase(settings),
        ),
        scope = scope,
        onRepositoryOpened = onOpened,
        pathExists = { it == PRESENT },
    )

    test("최근 저장소는 설정에 저장된 순서(앞이 최신) 그대로 표시된다") {
        val state = stateWith(settings = FakeSettingsGateway(settingsWith(listOf(MISSING, PRESENT))))

        state.refresh()

        state.screenState.recentRepositories.map { it.path } shouldContainExactly listOf(MISSING, PRESENT)
    }

    test("사라진 경로는 사용할 수 없음으로 표시되고 자동으로 제거되지 않는다") {
        val settings = FakeSettingsGateway(settingsWith(listOf(PRESENT, MISSING)))
        val state = stateWith(settings = settings)

        state.refresh()

        state.screenState.recentRepositories shouldContainExactly listOf(
            RecentRepository(PRESENT, available = true),
            RecentRepository(MISSING, available = false),
        )
        settings.saveCount shouldBe 0
    }

    test("사용자가 제거하면 그때 목록에서 빠지고 설정이 저장된다") {
        val settings = FakeSettingsGateway(settingsWith(listOf(PRESENT, MISSING)))
        val state = stateWith(settings = settings)
        state.refresh()

        state.forget(MISSING)

        state.screenState.recentRepositories.map { it.path } shouldContainExactly listOf(PRESENT)
        settings.stored.recentRepositories shouldContainExactly listOf(PRESENT)
    }

    test("최근 저장소가 0건이면 빈 목록으로 남는다") {
        val state = stateWith(settings = FakeSettingsGateway(settingsWith()))

        state.refresh()

        state.screenState.recentRepositories shouldContainExactly emptyList()
    }

    test("저장소를 열면 최근 목록 맨 앞으로 올라가고 열림 이벤트가 나간다") {
        val settings = FakeSettingsGateway(settingsWith(listOf(MISSING, PRESENT)))
        val opened = mutableListOf<RepositoryPath>()
        val state = stateWith(settings = settings, onOpened = opened::add)

        state.open(PRESENT)

        opened shouldContainExactly listOf(PRESENT)
        settings.stored.recentRepositories shouldContainExactly listOf(PRESENT, MISSING)
        state.screenState.recentRepositories.map { it.path } shouldContainExactly listOf(PRESENT, MISSING)
        state.screenState.notice.shouldBeNull()
    }

    test("네 가지 열기 실패 사유가 서로 다른 안내로 구분된다") {
        Reason.entries.forEach { reason ->
            val repositories = FakeRepositoryGateway(
                failures = mapOf(PRESENT to UndineException.InvalidRepositoryPath(PRESENT.value, reason)),
            )
            val opened = mutableListOf<RepositoryPath>()
            val state = stateWith(repositories = repositories, onOpened = opened::add)

            state.open(PRESENT)

            state.screenState.notice shouldBe WelcomeNotice.OpenFailed(reason)
            opened shouldContainExactly emptyList()
        }
    }

    test("경로 사유로 설명되지 않는 열기 실패도 안내로 남고 화면을 죽이지 않는다") {
        val repositories = FakeRepositoryGateway(
            failures = mapOf(PRESENT to UndineException.GitOperationFailed(operation = "open")),
        )
        val opened = mutableListOf<RepositoryPath>()
        val state = stateWith(repositories = repositories, onOpened = opened::add)

        state.open(PRESENT)

        state.screenState.notice shouldBe WelcomeNotice.OpenFailedUnexpectedly
        opened shouldContainExactly emptyList()
    }

    test("clone 이 완료되면 최근 목록에 저장되고 열림 이벤트가 나간다") {
        val target = RepositoryPath(File(tempdir(), "cloned").path)
        val settings = FakeSettingsGateway(settingsWith())
        val opened = mutableListOf<RepositoryPath>()
        val state = stateWith(
            settings = settings,
            remote = FakeRemoteGateway(progressUpdates = listOf(Progress(0.5, "Receiving objects"))),
            onOpened = opened::add,
        )

        state.startClone(REMOTE_URL, target.value)

        opened shouldContainExactly listOf(target)
        settings.stored.recentRepositories shouldContainExactly listOf(target)
        state.screenState.cloning shouldBe false
        state.screenState.cloneProgress.shouldBeNull()
    }

    test("비어 있지 않은 대상 디렉터리는 clone 을 시작하기 전에 거부된다") {
        val occupied = tempdir()
        File(occupied, "README.md").writeText("keep")
        val remote = FakeRemoteGateway()
        val state = stateWith(remote = remote)

        state.startClone(REMOTE_URL, occupied.path)

        state.screenState.notice shouldBe WelcomeNotice.TargetNotEmpty
        remote.cloneCount shouldBe 0
        state.screenState.cloning shouldBe false
    }

    test("인증 실패 안내에는 자격증명도 원격 URL 도 담기지 않는다") {
        val target = File(tempdir(), "auth")
        val state = stateWith(
            remote = FakeRemoteGateway(failure = UndineException.AuthenticationFailed(remote = "origin")),
        )

        state.startClone(REMOTE_URL, target.path)

        state.screenState.notice shouldBe WelcomeNotice.AuthenticationFailed
        state.screenState.notice.toString() shouldNotContain "secret-token"
        state.screenState.notice.toString() shouldNotContain "example.invalid"
        state.screenState.cloning shouldBe false
    }

    test("정리에 실패하면 원인 대신 수동으로 지울 경로를 안내한다") {
        val target = File(tempdir(), "locked")
        val settings = FakeSettingsGateway(settingsWith())
        val state = WelcomeState(
            actions = WelcomeActions(
                loadRecentRepositories = LoadRecentRepositoriesUseCase(settings),
                openRepository = OpenRepositoryUseCase(FakeRepositoryGateway(), settings),
                cloneRepository = CloneRepositoryUseCase(
                    FakeRemoteGateway(failure = UndineException.GitOperationFailed(operation = "clone")),
                    settings,
                    ioDispatcher = Dispatchers.Unconfined,
                    // 실제 삭제 실패(권한·잠금)는 OS 의존이라 재현이 불안정해 이음매로 고정한다.
                    deleteDirectory = { false },
                ),
                forgetRecentRepository = ForgetRecentRepositoryUseCase(settings),
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onRepositoryOpened = { },
            pathExists = { true },
        )

        state.startClone(REMOTE_URL, target.path)

        // 남는 것은 대상이 아니라 대상 옆의 앱 전용 스테이징 디렉터리다 — 대상에는 손대지 않는다.
        val notice = state.screenState.notice.shouldBeInstanceOf<WelcomeNotice.CleanupFailed>()
        notice.path.value shouldNotBe target.path
        File(notice.path.value).parentFile shouldBe target.absoluteFile.parentFile
        state.screenState.cloning shouldBe false
    }

    test("취소 도중 정리에 실패해도 수동으로 지울 경로가 안내된다") {
        val target = File(tempdir(), "cancelled-locked")
        val settings = FakeSettingsGateway(settingsWith())
        val state = WelcomeState(
            actions = WelcomeActions(
                loadRecentRepositories = LoadRecentRepositoriesUseCase(settings),
                openRepository = OpenRepositoryUseCase(FakeRepositoryGateway(), settings),
                cloneRepository = CloneRepositoryUseCase(
                    FakeRemoteGateway(
                        progressUpdates = listOf(Progress(0.2, "Receiving objects")),
                        suspendUntil = CompletableDeferred(),
                    ),
                    settings,
                    deleteDirectory = { false },
                ),
                forgetRecentRepository = ForgetRecentRepositoryUseCase(settings),
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onRepositoryOpened = { },
            pathExists = { true },
        )

        state.startClone(REMOTE_URL, target.path)
        while (state.screenState.cloneProgress == null) yield()
        state.cancelClone()
        while (state.screenState.cloning) yield()

        val notice = state.screenState.notice.shouldBeInstanceOf<WelcomeNotice.CleanupFailed>()
        File(notice.path.value).parentFile shouldBe target.absoluteFile.parentFile
        settings.saveCount shouldBe 0
    }

    test("clone 입력란의 글자는 상태 홀더가 소유한다") {
        val state = stateWith()

        state.changeCloneUrl(REMOTE_URL)
        state.changeCloneTarget("/tmp/undine-clone")

        state.screenState.cloneUrl shouldBe REMOTE_URL
        state.screenState.cloneTarget shouldBe "/tmp/undine-clone"
    }

    test("clone 을 취소하면 진행이 멈추고 성공으로 표시되지 않는다") {
        val target = File(tempdir(), "cancelled")
        val settings = FakeSettingsGateway(settingsWith())
        val opened = mutableListOf<RepositoryPath>()
        val state = stateWith(
            settings = settings,
            remote = FakeRemoteGateway(
                progressUpdates = listOf(Progress(0.2, "Receiving objects")),
                suspendUntil = CompletableDeferred(),
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onOpened = opened::add,
        )

        state.startClone(REMOTE_URL, target.path)
        while (state.screenState.cloneProgress == null) yield()
        state.cancelClone()
        while (state.screenState.cloning) yield()

        opened shouldContainExactly emptyList()
        settings.saveCount shouldBe 0
        target.exists() shouldBe false
    }

    test("clone 이 진행 중이면 진행률과 단계가 노출된다") {
        val target = File(tempdir(), "inflight")
        val state = stateWith(
            remote = FakeRemoteGateway(
                progressUpdates = listOf(Progress(0.4, "Receiving objects")),
                suspendUntil = CompletableDeferred(),
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        state.startClone(REMOTE_URL, target.path)
        while (state.screenState.cloneProgress == null) yield()

        state.screenState.cloning shouldBe true
        state.screenState.cloneProgress shouldBe Progress(0.4, "Receiving objects")

        state.cancelClone()
        while (state.screenState.cloning) yield()
    }

    test("진행 중에 다시 요청해도 clone 이 겹쳐 시작되지 않는다") {
        val target = File(tempdir(), "duplicate")
        val remote = FakeRemoteGateway(
            progressUpdates = listOf(Progress(0.3, "Receiving objects")),
            suspendUntil = CompletableDeferred(),
        )
        val state = stateWith(
            remote = remote,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        state.startClone(REMOTE_URL, target.path)
        while (state.screenState.cloneProgress == null) yield()
        state.startClone(REMOTE_URL, target.path)

        remote.cloneCount shouldBe 1

        state.cancelClone()
        while (state.screenState.cloning) yield()
    }

    test("안내를 닫으면 사라진다") {
        val occupied = tempdir()
        File(occupied, "keep.txt").writeText("keep")
        val state = stateWith()
        state.startClone(REMOTE_URL, occupied.path)

        state.dismissNotice()

        state.screenState.notice.shouldBeNull()
    }
})
