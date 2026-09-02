package dev.undine.scenario2

import dev.undine.application.bisect.MarkBisectUseCase
import dev.undine.application.bisect.ResetBisectUseCase
import dev.undine.application.bisect.RestoreBisectSessionUseCase
import dev.undine.application.bisect.StartBisectUseCase
import dev.undine.application.blame.LoadBlameUseCase
import dev.undine.application.blame.LoadFileHistoryUseCase
import dev.undine.application.cherrypick.CherryPickCommitsUseCase
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.application.externaltool.OpenDiffToolUseCase
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.application.graphops.ExecuteGraphOperationUseCase
import dev.undine.application.reflog.RecoveryActionService
import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.RecoveryBisectUseCases
import dev.undine.application.session.RepositorySessionUseCase
import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoService
import dev.undine.application.undo.UndoTarget
import dev.undine.application.welcome.OpenRepositoryUseCase
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.cherrypick.CherryPickService
import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.undo.UndoStack
import dev.undine.infrastructure.externaltool.ExternalToolGatewayImpl
import dev.undine.infrastructure.git.bisect.BisectGatewayImpl
import dev.undine.infrastructure.git.blame.BlameGatewayImpl
import dev.undine.infrastructure.git.cherrypick.CherryPickGatewayImpl
import dev.undine.infrastructure.git.diff.DiffGatewayImpl
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.reflog.ReflogGatewayImpl
import dev.undine.infrastructure.git.remote.RemoteGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.repository.RepositorySessionGatewayImpl
import dev.undine.infrastructure.git.signing.SigningGatewayImpl
import dev.undine.infrastructure.git.staging.StagingGatewayImpl
import dev.undine.infrastructure.git.submodule.SubmoduleGatewayImpl
import dev.undine.infrastructure.git.worktree.WorktreeGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.TimeZone

internal const val MAIN_BRANCH = "main"

/** 시나리오가 직접 만드는 커밋의 고정 시각·작성자. 실행 시각에 결과가 흔들리지 않게 한다. */
internal val FIXED_INSTANT: Instant = Instant.parse("2026-02-01T09:00:00Z")
internal val FIXED_IDENT: PersonIdent =
    PersonIdent("undine", "undine@example.invalid", Date.from(FIXED_INSTANT), TimeZone.getTimeZone("UTC"))

/**
 * 2차 기능의 **실제 저장소 + 앱 조립**. `scenario` 패키지의 `ScenarioApp` 이 1차에 한 일의 2차 판이다.
 *
 * `AppComponent` 를 쓰지 않고 여기서 다시 조립하되, 조립 순서·구현 선택은 `AppComponent` 와 같게
 * 유지한다 — 여기가 앞서 나가면 시나리오가 앱과 다른 것을 검증한다. `GitAccess` 는 저장소당 **하나**여야
 * 하므로 (동시 접근 직렬화 경계) 이 클래스가 그 하나를 소유하고 모든 Gateway 가 공유한다.
 *
 * 1차 조립(`ScenarioApp`)을 재사용하지 않는 이유는 그것이 2차 축을 배선하지 않기 때문이다 — reflog ·
 * bisect · blame · submodule · worktree · signing · 외부 도구 · 탭 세션이 여기서만 이어진다.
 *
 * **Mock 을 쓰지 않는다** (`testing` 규칙 1). JGit 도 Gateway 도 대역으로 바꾸지 않는다 — 이어 붙였을
 * 때 깨지는 것이 이 시나리오가 잡으려는 것이다.
 *
 * ### 이 티켓에서 제외한 원문 시나리오와 그 사유
 *
 * 티켓 원문의 시나리오 5(patch 내보내기 → 다른 클론에 적용)와 시나리오 10 중 **LFS 미설치 안내**는
 * 이 티켓에서 수행하지 않는다. `domain/patch` · `domain/lfs` 와 그에 대응하는 Gateway · UseCase 가
 * 현재 소스에 **존재하지 않기** 때문이다 — E2E 시나리오는 이미 이어진 경로를 실제 저장소로 확인하는
 * 자리이고, 검증 대상을 만들려고 기능을 신설하는 것은 이 티켓의 범위가 아니다. 두 기능이 생기면
 * 그 티켓이 여기에 시나리오를 더한다.
 *
 * 시나리오 10 의 나머지 둘은 **기존 실패 경로**로 검증한다: 외부 도구는
 * `ProcessExternalToolRunner.isInstalled` 의 미설치 판정, 서명은 `SigningGateway.sign()` 의
 * `SignResult.Failed`. 새 안내 문구 계약을 테스트가 만들어 내지 않는다.
 */
@Suppress("LongParameterList") // 조립 결과를 그대로 노출하는 값 객체다 — 묶으면 시나리오가 더 길어진다.
internal class Scenario2App(val work: File, settingsFile: File) {

    private val gitAccess = GitAccess()

    // ── Gateway (infrastructure) — AppComponent 와 같은 순서·같은 구현 ──
    private val repositoryGateway = RepositoryGatewayImpl(gitAccess)
    private val refGateway = RefGatewayImpl(gitAccess)
    private val historyGateway = HistoryGatewayImpl(gitAccess)
    private val diffGateway = DiffGatewayImpl(gitAccess)
    private val stagingGateway = StagingGatewayImpl(gitAccess)
    private val remoteGateway = RemoteGatewayImpl(gitAccess)
    private val worktreeOpsGateway = WorktreeOpsGatewayImpl(gitAccess)

    /** 설정 파일은 **저장소 밖**에 둔다 — 안에 두면 추적되지 않는 파일로 잡혀 상태 검증이 흔들린다. */
    private val settingsGateway = SettingsGatewayImpl(settingsFile.toPath())

    private val repositorySessionGateway = RepositorySessionGatewayImpl(gitAccess)

    // ── Gateway — 2차 기능 ──
    private val blameGateway = BlameGatewayImpl(gitAccess)
    private val reflogGateway = ReflogGatewayImpl(gitAccess)
    private val bisectGateway = BisectGatewayImpl(gitAccess)
    private val submoduleGateway = SubmoduleGatewayImpl(gitAccess)
    private val worktreeGateway = WorktreeGatewayImpl(gitAccess)
    private val cherryPickGateway = CherryPickGatewayImpl(gitAccess)
    private val externalToolGateway = ExternalToolGatewayImpl(gitAccess, settingsGateway)

    private val bisectService = BisectService(bisectGateway)
    private val cherryPickService = CherryPickService(repositoryGateway, cherryPickGateway)

    /** 되돌리기 이력과 그 이력에 기록하는 실행 경로. 배선(`AppComponent.RepositoryUndoScope`)과 같은 조합이다. */
    val undoStack = UndoStack()
    private val operationRecorder = OperationRecorder(refGateway, undoStack, changeRecordingOrder = gitAccess)
    private val undoService = UndoService(undoStack, refGateway, repositoryGateway, worktreeOpsGateway)

    // ── UseCase (application) ──
    val openRepository = OpenRepositoryUseCase(repositoryGateway, settingsGateway)
    val loadStatus = LoadWorkingTreeStatusUseCase(repositoryGateway)
    val stageFiles = StageFilesUseCase(stagingGateway)
    val commitStaged = CommitStagedUseCase(stagingGateway, operationRecorder)
    val checkoutBranch = CheckoutBranchUseCase(refGateway, operationRecorder)
    val loadHistory = LoadCommitHistoryUseCase(historyGateway)
    val cherryPickCommits = CherryPickCommitsUseCase(cherryPickService, operationRecorder)
    val pushRemote = PushRemoteUseCase(remoteGateway, operationRecorder)
    val executeGraphOperation = ExecuteGraphOperationUseCase(worktreeOpsGateway, refGateway, operationRecorder)

    val peekUndoTarget = PeekUndoTargetUseCase(undoService)
    val undoLastOperation = UndoLastOperationUseCase(undoService)

    val loadBlame = LoadBlameUseCase(blameGateway)
    val loadFileHistory = LoadFileHistoryUseCase(blameGateway)

    val loadSubmodules = LoadSubmodulesUseCase(submoduleGateway)
    val initializeSubmodule = InitializeSubmoduleUseCase(submoduleGateway, operationRecorder)
    val updateSubmodule = UpdateSubmoduleUseCase(submoduleGateway, operationRecorder)

    val loadWorktrees = LoadWorktreesUseCase(worktreeGateway)
    val addWorktree = AddWorktreeUseCase(worktreeGateway, operationRecorder)
    val removeWorktree = RemoveWorktreeUseCase(worktreeGateway, operationRecorder)

    val openDiffTool = OpenDiffToolUseCase(externalToolGateway)
    val checkToolAvailability = CheckToolAvailabilityUseCase(externalToolGateway)

    /** Reflog 복구·bisect 화면이 부르는 application 경계. */
    val recoveryActions: RecoveryActions = RecoveryActionService(
        reflogGateway = reflogGateway,
        historyGateway = historyGateway,
        diffGateway = diffGateway,
        bisect = RecoveryBisectUseCases(
            start = StartBisectUseCase(bisectService),
            mark = MarkBisectUseCase(bisectService),
            reset = ResetBisectUseCase(bisectService),
            restore = RestoreBisectSessionUseCase(bisectService),
        ),
        operationRecorder = operationRecorder,
    )

    /** 저장소 탭의 열기·활성화·닫기. 탭 시나리오는 이 경로만 쓴다 (단일 저장소 열기와 섞지 않는다). */
    val repositorySession = RepositorySessionUseCase(repositorySessionGateway, settingsGateway)

    /**
     * 전용 UseCase 가 없는 축은 계약을 그대로 쓴다 — `ScenarioApp` 이 `refs`·`worktreeOps` 를 여는 것과 같다.
     * 서명은 `sign()` 을 부르는 UseCase 가 아직 없어 화면이 아니라 계약이 유일한 소비 지점이다.
     */
    val signing: SigningGateway = SigningGatewayImpl(gitAccess)

    /** 앱이 저장소를 여는 그 경로로 연다 — 시나리오가 뒷문을 쓰지 않는다. */
    suspend fun open() = openRepository.execute(RepositoryPath(work.absolutePath))

    /** 세션을 닫는다. 닫은 뒤의 Gateway 호출은 빈 결과가 아니라 실패여야 한다. */
    suspend fun close() = repositoryGateway.close()

    /** 활성 세션으로 저장소를 읽는다. 탭을 닫아 자원이 해제됐는지 관찰하는 자리다. */
    suspend fun <T> readActiveRepository(block: (Repository) -> T): T = gitAccess.withRepository(block)

    /** 앱 경로로 stage → commit. 시나리오의 기본 동작이다. */
    suspend fun stageAndCommit(message: String, vararg paths: String): CommitId {
        stageFiles.execute(paths.toList())
        return commitStaged.execute(message).result.commitId
    }

    fun writeFile(name: String, content: String) {
        File(work, name).writeText(content)
    }

    fun readFile(name: String): String = File(work, name).readText()

    /** 오래된 것부터의 커밋 메시지. 이력 검증의 기본 재료다. */
    fun messagesOldestFirst(): List<String> = messagesOldestFirstOf(work)

    fun head(): CommitId = headCommitId(work)

    /**
     * 화면과 같은 순서로 최상단 한 건을 되돌린다 — 미리 본 대상을 그대로 지목한다.
     *
     * @throws IllegalStateException 되돌릴 대상이 없거나 막혀 있을 때. 시나리오가 그 상태를 검증할
     *   때는 [peekUndoTarget] 을 직접 쓴다.
     */
    suspend fun undoTop(): UndoExecution {
        val target = peekUndoTarget.execute()
        check(target is UndoTarget.Undoable) { "되돌릴 수 있는 최상단 기록이 없습니다: $target" }
        return undoLastOperation.execute(target.entry)
    }
}

/**
 * 커밋 하나가 있는 임시 저장소를 만들고 **앱이 저장소를 여는 경로 그대로** 열어 [block] 에 넘긴다.
 *
 * `tempdir()` 은 Kotest 가 스펙 종료 시 지우는 디렉터리라 시나리오마다 독립이고 디렉터리 정리도
 * 보장된다. 다만 디렉터리를 지우는 것이 **열린 JGit 핸들을 닫아 주지는 않는다** — 그래서 세션 종료는
 * [use] 가 `finally` 로 보장한다. 설정 파일은 저장소 **밖**(형제 자리)에 둔다 — 안에 두면 추적되지
 * 않는 파일로 잡힌다.
 */
internal suspend fun TestConfiguration.openedApp(work: File = tempdir(), block: suspend (Scenario2App) -> Unit) {
    seedRepository(work)
    scenario2AppAt(work).use { app ->
        app.open()
        block(app)
    }
}

/** 저장소를 열지 않은 조립. 탭 세션 시나리오는 단일 저장소 열기 경로와 섞지 않는다. */
internal fun scenario2AppAt(work: File): Scenario2App =
    Scenario2App(work, File(work.parentFile, "${work.name}-settings.json"))

/**
 * [block] 을 수행하고 **어느 경로로 끝나도** 세션을 닫는다.
 *
 * 시나리오가 단정 실패로 빠져나가도 열린 저장소 핸들이 남지 않아야 한다 (`jgit-usage` 의 자원 수명).
 * 남으면 같은 JVM 의 뒤 스펙이 그 핸들의 캐시된 상태를 보고 흔들린다. [Scenario2App.close] 는 열려
 * 있지 않을 때 아무 일도 하지 않으므로, 시나리오가 스스로 탭을 모두 닫은 뒤에도 그대로 부를 수 있다.
 *
 * 정리는 [NonCancellable] 단위다 (결정 A-L2·G46). `close()` 가 결국 닿는
 * `GitAccess.close()` 는 `withContext(Dispatchers.IO)` 라, 취소된 컨텍스트에서는 블록을 **아예
 * 실행하지 않고** 던진다 — `finally` 만 쓰면 정리된다고 믿게 만들면서 JGit 세션이 그대로 남는다.
 * 취소 자체는 삼키지 않고 그대로 전파된다.
 */
internal suspend fun Scenario2App.use(block: suspend (Scenario2App) -> Unit) {
    try {
        block(this)
    } finally {
        withContext(NonCancellable) { close() }
    }
}

/**
 * 커밋 하나가 있는 저장소를 만든다. **셋업은 JGit 을 직접 쓴다** — 저장소 생성은 앱 기능이 아니고,
 * 셋업 커밋의 시각을 고정해야 결과가 실행 시각에 흔들리지 않는다.
 */
internal fun seedRepository(work: File, file: String = "base.txt", content: String = "base\n"): File {
    require(work.exists() || work.mkdirs()) { "임시 디렉토리를 만들지 못했다: $work" }
    Git.init().setDirectory(work).setInitialBranch(MAIN_BRANCH).call().use { git ->
        git.configureLocalIdentity()
        File(work, file).writeText(content)
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
    }
    return work
}

/**
 * 저장소 **로컬** 작성자 설정. 앱의 커밋 경로는 작성자가 설정돼 있지 않으면 커밋하지 않는다 —
 * 전역 설정에 기대면 개발자 머신에서만 통과하고 CI 에서 깨진다.
 */
internal fun Git.configureLocalIdentity() {
    repository.config.apply {
        setString("user", null, "name", FIXED_IDENT.name)
        setString("user", null, "email", FIXED_IDENT.emailAddress)
        save()
    }
}

/** 셋업이 직접 쌓는 커밋. 앱 경로가 아니라 전제를 만드는 자리에서만 쓴다. */
internal fun Git.commitFile(work: File, name: String, content: String, message: String): CommitId {
    File(work, name).writeText(content)
    add().addFilepattern(name).call()
    return CommitId.of(
        commit().setMessage(message).setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call().name,
    )
}

/** 현재 HEAD 가 가리키는 커밋. 복구 검증의 기준점이다. */
internal fun headCommitId(work: File): CommitId =
    Git.open(work).use { git -> CommitId.of(git.repository.resolve(Constants.HEAD).name) }

internal fun messagesOldestFirstOf(work: File): List<String> =
    Git.open(work).use { git -> git.log().call().map { it.fullMessage.trim() }.reversed() }

internal fun mainRef(): RefName = RefName("refs/heads/$MAIN_BRANCH")
