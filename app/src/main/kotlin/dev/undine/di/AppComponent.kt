package dev.undine.di

import dev.undine.application.commitdetail.LoadChangedFilesUseCase
import dev.undine.application.diff.LoadFileDiffUseCase
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.application.staging.AmendCommitUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.staging.StageHunksUseCase
import dev.undine.application.staging.UnstageFilesUseCase
import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.sidebar.DeleteBranchUseCase
import dev.undine.application.sidebar.LoadSidebarRefsUseCase
import dev.undine.application.sidebar.RenameBranchUseCase
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.application.welcome.CloneRepositoryUseCase
import dev.undine.application.welcome.ForgetRecentRepositoryUseCase
import dev.undine.application.welcome.LoadRecentRepositoriesUseCase
import dev.undine.application.welcome.OpenRepositoryUseCase
import dev.undine.domain.RefGateway
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway
import dev.undine.infrastructure.git.diff.DiffGatewayImpl
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.staging.StagingGatewayImpl
import dev.undine.infrastructure.git.remote.RemoteGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import dev.undine.presentation.staging.StagingActions
import dev.undine.presentation.welcome.WelcomeActions
import java.nio.file.Path

/**
 * Gateway 구현체 → UseCase 를 **명시적 생성자 조립**으로 엮는다.
 *
 * 프레임워크 DI 를 쓰지 않는다 — 이 규모에서 컨테이너는 "무엇이 무엇을 받는가" 를 감추기만 한다.
 * 여기 한 파일을 읽으면 앱 전체의 의존 그래프가 보인다.
 *
 * **[gitAccess] 하나를 전 Gateway 가 공유한다.** JGit `Repository` 는 스레드 안전하지 않아
 * 동시 접근 직렬화와 IO 디스패처 전환을 그 경계가 책임진다 — Gateway 마다 따로 만들면
 * 같은 저장소에 두 개의 직렬화 경계가 생겨 직렬화가 깨진다.
 *
 * 저장소 핸들은 세션 단위로 열어 두고 저장소를 바꿀 때만 닫는다 ([closeRepository]).
 *
 * @param settingsFile 설정 영속화 위치. 창 소유자가 정한다 — 컴포넌트가 경로 정책을 만들지 않는다.
 */
class AppComponent(settingsFile: Path) {

    private val gitAccess = GitAccess()

    // ── Gateway (infrastructure) ──
    private val repositoryGateway: RepositoryGateway = RepositoryGatewayImpl(gitAccess)
    private val refGateway: RefGateway = RefGatewayImpl(gitAccess)
    private val historyGateway = HistoryGatewayImpl(gitAccess)
    private val diffGateway = DiffGatewayImpl(gitAccess)
    private val remoteGateway = RemoteGatewayImpl(gitAccess)
    private val worktreeOpsGateway = WorktreeOpsGatewayImpl(gitAccess)
    private val stagingGateway = StagingGatewayImpl(gitAccess)
    private val settingsGateway: SettingsGateway = SettingsGatewayImpl(settingsFile)

    // ── UseCase (application) ──
    /** 환영 화면이 쓰는 네 동작. 화면은 이 묶음만 받고 Gateway 를 알지 못한다. */
    val welcomeActions = WelcomeActions(
        loadRecentRepositories = LoadRecentRepositoriesUseCase(settingsGateway),
        openRepository = OpenRepositoryUseCase(repositoryGateway, settingsGateway),
        cloneRepository = CloneRepositoryUseCase(remoteGateway, settingsGateway),
        forgetRecentRepository = ForgetRecentRepositoryUseCase(settingsGateway),
    )

    val loadSidebarRefs = LoadSidebarRefsUseCase(refGateway, worktreeOpsGateway)
    val checkoutBranch = CheckoutBranchUseCase(refGateway)
    val renameBranch = RenameBranchUseCase(refGateway)
    val deleteBranch = DeleteBranchUseCase(refGateway)

    val loadCommitHistory = LoadCommitHistoryUseCase(historyGateway)
    val searchCommits = SearchCommitsUseCase(historyGateway, diffGateway)

    val loadChangedFiles = LoadChangedFilesUseCase(diffGateway)
    val loadFileDiff = LoadFileDiffUseCase(diffGateway)

    /** 스테이징 패널이 쓰는 동작 묶음. 패널이 인덱스 상태의 단일 소유자다. */
    val stagingActions = StagingActions(
        loadStatus = LoadWorkingTreeStatusUseCase(repositoryGateway),
        stageFiles = StageFilesUseCase(stagingGateway),
        unstageFiles = UnstageFilesUseCase(stagingGateway),
        stageHunks = StageHunksUseCase(stagingGateway),
        commitStaged = CommitStagedUseCase(stagingGateway),
        amendCommit = AmendCommitUseCase(stagingGateway),
    )

    val fetchRemote = FetchRemoteUseCase(remoteGateway)
    val pullRemote = PullRemoteUseCase(remoteGateway)
    val pushRemote = PushRemoteUseCase(remoteGateway)

    /**
     * 저장소를 연다. 화면 전환 전에 여기서 열어야 이후 Gateway 호출이 같은 핸들을 본다.
     *
     * 실패는 그대로 올린다 — 안내 문구로 바꾸는 것은 화면의 몫이다.
     */
    suspend fun openRepository(path: RepositoryPath) =
        welcomeActions.openRepository.execute(path)

    /**
     * 원격 이름 목록.
     *
     * **지금은 비어 있다.** `RemoteGateway` 에 원격을 열거하는 계약이 없고, 결정문 A4 가
     * "이미 있는 domain 최상위 계약 파일은 고치지 않는다 — 고쳐야 하면 구현하지 말고 보고한다" 로
     * 못박아 두었다. 툴바는 빈 목록을 받으면 `NO_REMOTE` 사유를 표시하고 원격 버튼을 닫는다 —
     * 잘못된 대상으로 fetch·push 하는 것보다 낫다. 계약 확장 여부는 사람 결정 대기 중이다.
     */
    fun knownRemotes(): List<String> = emptyList()

    /** 참조 목록. 그래프·검색이 어느 ref 를 훑을지와 참조 칩 색인의 재료다. */
    suspend fun listBranches() = refGateway.listBranches()

    suspend fun listTags() = refGateway.listTags()

    /**
     * 열린 저장소 핸들을 닫는다. 창을 닫을 때와 저장소를 바꿀 때 호출한다 —
     * 닫지 않으면 JGit 파일 핸들이 세션 동안 남는다.
     */
    suspend fun closeRepository() = repositoryGateway.close()
}
