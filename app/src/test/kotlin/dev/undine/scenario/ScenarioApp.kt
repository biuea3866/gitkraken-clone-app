package dev.undine.scenario

import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.conflict.LoadConflictContentUseCase
import dev.undine.application.conflict.LoadConflictedFilesUseCase
import dev.undine.application.conflict.ResolveConflictUseCase
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.application.merge.AbortMergeOrRebaseUseCase
import dev.undine.application.merge.MergeBranchUseCase
import dev.undine.application.rebase.ApplyRebasePlanUseCase
import dev.undine.application.rebase.LoadRebaseTargetsUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.application.welcome.OpenRepositoryUseCase
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.merge.MergeService
import dev.undine.infrastructure.git.conflict.ConflictGatewayImpl
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.merge.MergeGatewayImpl
import dev.undine.infrastructure.git.rebase.InteractiveRebaseGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.remote.RemoteGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.staging.StagingGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.TimeZone

internal const val MAIN_BRANCH = "main"

/** 시나리오가 직접 만드는 커밋의 고정 시각·작성자. 실행 시각에 결과가 흔들리지 않게 한다. */
internal val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T09:00:00Z")
internal val FIXED_IDENT: PersonIdent =
    PersonIdent("undine", "undine@example.invalid", Date.from(FIXED_INSTANT), TimeZone.getTimeZone("UTC"))

/**
 * 시나리오 하나가 쓰는 **실제 저장소 + 앱 조립**.
 *
 * `AppComponent` 를 쓰지 않고 여기서 다시 조립한다. 이유는 두 가지다.
 * - 병합·리베이스 **시작** 경로는 아직 어느 화면도 노출하지 않아 `AppComponent` 에 없다. 시나리오
 *   2·3·4·7 은 그 경로가 필요하다.
 * - `GitAccess` 는 저장소당 **하나**여야 한다 (동시 접근 직렬화 경계). 두 조립을 섞으면 경계가
 *   둘이 되어 직렬화가 깨진다.
 *
 * 대신 조립 순서·구현 선택은 `AppComponent` 와 같게 유지한다 — 여기가 앞서 나가면 시나리오가 앱과
 * 다른 것을 검증한다.
 *
 * **Mock 을 쓰지 않는다.** JGit 을 대역으로 바꾸면 이어 붙였을 때 깨지는 것을 검증하지 못한다
 * (`testing` 규칙 1).
 */
@Suppress("LongParameterList") // 조립 결과를 그대로 노출하는 값 객체다 — 묶으면 시나리오가 더 길어진다.
internal class ScenarioApp(val work: File) {

    private val gitAccess = GitAccess()

    private val repositoryGateway = RepositoryGatewayImpl(gitAccess)
    private val refGateway = RefGatewayImpl(gitAccess)
    private val historyGateway = HistoryGatewayImpl(gitAccess)
    private val stagingGateway = StagingGatewayImpl(gitAccess)
    private val conflictGateway = ConflictGatewayImpl(gitAccess)
    private val mergeGateway = MergeGatewayImpl(gitAccess)
    private val rebaseGateway = InteractiveRebaseGatewayImpl(gitAccess)
    private val remoteGateway = RemoteGatewayImpl(gitAccess)
    private val worktreeOpsGateway = WorktreeOpsGatewayImpl(gitAccess)
    // 설정 파일은 **저장소 밖**에 둔다 — 워킹트리 안에 두면 추적되지 않는 파일로 잡혀 상태 검증이 흔들린다.
    private val settingsGateway =
        SettingsGatewayImpl(File(work.parentFile, "${work.name}-settings.json").toPath())

    private val mergeService = MergeService(repositoryGateway, mergeGateway)

    val openRepository = OpenRepositoryUseCase(repositoryGateway, settingsGateway)
    val loadStatus = LoadWorkingTreeStatusUseCase(repositoryGateway)
    val stageFiles = StageFilesUseCase(stagingGateway)
    val commitStaged = CommitStagedUseCase(stagingGateway)
    val loadHistory = LoadCommitHistoryUseCase(historyGateway)
    val mergeBranch = MergeBranchUseCase(mergeService)
    val continueAfterResolve = ContinueAfterResolveUseCase(mergeService)
    val abortMergeOrRebase = AbortMergeOrRebaseUseCase(mergeService)
    val loadConflicted = LoadConflictedFilesUseCase(conflictGateway)
    val loadConflictContent = LoadConflictContentUseCase(conflictGateway)
    val resolveConflict = ResolveConflictUseCase(conflictGateway)
    val loadRebaseTargets = LoadRebaseTargetsUseCase(rebaseGateway)
    val applyRebasePlan = ApplyRebasePlanUseCase(rebaseGateway)
    val pushRemote = PushRemoteUseCase(remoteGateway)
    val fetchRemote = FetchRemoteUseCase(remoteGateway)

    /** 저장소 참조·상태 조회는 시나리오가 직접 쓴다 (전용 UseCase 가 없는 축). */
    val refs = refGateway
    val worktreeOps = worktreeOpsGateway

    /** 앱이 저장소를 여는 그 경로로 연다 — 시나리오가 뒷문을 쓰지 않는다. */
    suspend fun open() = openRepository.execute(RepositoryPath(work.absolutePath))

    /** 세션을 닫는다. 닫은 뒤의 Gateway 호출은 빈 결과가 아니라 실패여야 한다. */
    suspend fun close() = repositoryGateway.close()

    /** 앱 경로로 stage → commit. 시나리오의 기본 동작이다. */
    suspend fun stageAndCommit(message: String, vararg paths: String): CommitId {
        stageFiles.execute(paths.toList())
        return commitStaged.execute(message).commitId
    }

    fun writeFile(name: String, content: String) {
        File(work, name).writeText(content)
    }

    fun readFile(name: String): String = File(work, name).readText()

    /** 오래된 것부터의 커밋 메시지. 이력 검증의 기본 재료다. */
    fun messagesOldestFirst(): List<String> =
        Git.open(work).use { git -> git.log().call().map { it.fullMessage.trim() }.reversed() }
}

/**
 * 커밋 하나가 있는 저장소를 만든다. **셋업은 JGit 을 직접 쓴다** — 저장소 생성은 앱 기능이 아니고
 * (init 은 어느 티켓도 노출하지 않는다), 셋업 커밋의 시각을 고정해야 결과가 실행 시각에 흔들리지 않는다.
 */
internal fun seedRepository(work: File, file: String = "base.txt", content: String = "base\n"): File {
    Git.init().setDirectory(work).setInitialBranch(MAIN_BRANCH).call().use { git ->
        File(work, file).writeText(content)
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
    }
    return work
}

/** 현재 HEAD 가 가리키는 커밋. 복구 검증의 기준점이다. */
internal fun headOf(work: File): String =
    Git.open(work).use { git -> git.repository.resolve("HEAD").name }

/** 브랜치를 만들 기준 커밋. 참조 이름 규칙에 기대지 않고 HEAD 를 그대로 읽는다. */
internal fun headCommitId(work: File): CommitId = CommitId.of(headOf(work))

internal fun mainRef(): RefName = RefName("refs/heads/$MAIN_BRANCH")
