package dev.undine.di

import dev.undine.application.bisect.MarkBisectUseCase
import dev.undine.application.bisect.ResetBisectUseCase
import dev.undine.application.bisect.RestoreBisectSessionUseCase
import dev.undine.application.bisect.StartBisectUseCase
import dev.undine.application.blame.CompareFileHistoryUseCase
import dev.undine.application.blame.LoadBlameUseCase
import dev.undine.application.blame.LoadFileHistoryUseCase
import dev.undine.application.commitdetail.LoadChangedFilesUseCase
import dev.undine.application.conflict.AbortConflictedOperationUseCase
import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.conflict.LoadConflictContentUseCase
import dev.undine.application.conflict.LoadConflictedFilesUseCase
import dev.undine.application.conflict.ResolveConflictUseCase
import dev.undine.application.diff.LoadFileDiffUseCase
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.externaltool.OpenDiffToolUseCase
import dev.undine.application.externaltool.OpenMergeToolUseCase
import dev.undine.application.graphops.ExecuteGraphOperationUseCase
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.application.identity.ApplyProfileUseCase
import dev.undine.application.identity.AssignedProfileNameUseCase
import dev.undine.application.identity.ClearLocalIdentityUseCase
import dev.undine.application.identity.DeleteProfileUseCase
import dev.undine.application.identity.IdentityUseCases
import dev.undine.application.identity.LoadProfilesUseCase
import dev.undine.application.identity.SaveProfileUseCase
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.LoadSigningPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.application.reflog.RecoveryActionService
import dev.undine.application.reflog.RecoveryActions
import dev.undine.application.reflog.RecoveryBisectUseCases
import dev.undine.application.staging.AmendCommitUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.staging.StageHunksUseCase
import dev.undine.application.staging.UnstageFilesUseCase
import dev.undine.application.rebase.ApplyRebasePlanUseCase
import dev.undine.application.rebase.LoadRebaseProgressUseCase
import dev.undine.application.rebase.LoadRebaseTargetsUseCase
import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.submodule.CommitSubmodulePointerUseCase
import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.sidebar.DeleteBranchUseCase
import dev.undine.application.sidebar.LoadSidebarRefsUseCase
import dev.undine.application.sidebar.RenameBranchUseCase
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.application.undo.DiscardBlockedUndoEntryUseCase
import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoService
import dev.undine.application.welcome.CloneRepositoryUseCase
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.application.welcome.ForgetRecentRepositoryUseCase
import dev.undine.application.welcome.LoadRecentRepositoriesUseCase
import dev.undine.application.welcome.OpenRepositoryUseCase
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefGateway
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.SettingsGateway
import dev.undine.domain.bisect.BisectGateway
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.blame.BlameGateway
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.identity.IdentityGateway
import dev.undine.domain.identity.IdentityService
import dev.undine.domain.merge.MergeGateway
import dev.undine.domain.merge.MergeService
import dev.undine.domain.rebase.InteractiveRebaseGateway
import dev.undine.domain.reflog.ReflogGateway
import dev.undine.domain.signing.SigningGateway
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.infrastructure.externaltool.ExternalToolGatewayImpl
import dev.undine.infrastructure.git.bisect.BisectGatewayImpl
import dev.undine.infrastructure.git.blame.BlameGatewayImpl
import dev.undine.infrastructure.git.conflict.ConflictGatewayImpl
import dev.undine.infrastructure.git.merge.MergeGatewayImpl
import dev.undine.infrastructure.git.rebase.InteractiveRebaseGatewayImpl
import dev.undine.infrastructure.git.diff.DiffGatewayImpl
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.reflog.ReflogGatewayImpl
import dev.undine.infrastructure.git.signing.SigningGatewayImpl
import dev.undine.infrastructure.git.staging.StagingGatewayImpl
import dev.undine.infrastructure.git.remote.RemoteGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.repository.toOpenedRepository
import dev.undine.infrastructure.git.submodule.SubmoduleGatewayImpl
import dev.undine.infrastructure.git.worktree.WorktreeGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import dev.undine.infrastructure.identity.IdentityGatewayImpl
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import dev.undine.presentation.conflict.ConflictActions
import dev.undine.presentation.rebase.RebaseActions
import dev.undine.presentation.submodule.SubmodulePanelActions
import dev.undine.presentation.submodule.WorktreePanelActions
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
    private val conflictGateway: ConflictGateway = ConflictGatewayImpl(gitAccess)
    private val mergeGateway: MergeGateway = MergeGatewayImpl(gitAccess)
    private val rebaseGateway: InteractiveRebaseGateway = InteractiveRebaseGatewayImpl(gitAccess)
    private val settingsGateway: SettingsGateway = SettingsGatewayImpl(settingsFile)

    // ── Gateway (infrastructure) — 2차 기능 ──
    private val blameGateway: BlameGateway = BlameGatewayImpl(gitAccess)
    private val reflogGateway: ReflogGateway = ReflogGatewayImpl(gitAccess)
    private val bisectGateway: BisectGateway = BisectGatewayImpl(gitAccess)
    private val submoduleGateway: SubmoduleGateway = SubmoduleGatewayImpl(gitAccess)
    private val worktreeGateway: WorktreeGateway = WorktreeGatewayImpl(gitAccess)
    private val signingGateway: SigningGateway = SigningGatewayImpl(gitAccess)
    private val identityGateway: IdentityGateway = IdentityGatewayImpl(gitAccess, settingsGateway)
    private val externalToolGateway: ExternalToolGateway = ExternalToolGatewayImpl(gitAccess, settingsGateway)

    /** 병합·리베이스의 규칙(시작 전 검사·진행 중 검사·확인 대조)을 갖는 도메인 서비스. */
    private val mergeService = MergeService(repositoryGateway, mergeGateway)

    private val identityService = IdentityService(identityGateway, historyGateway)
    private val bisectService = BisectService(bisectGateway)

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

    /**
     * 충돌 에디터가 쓰는 동작 묶음.
     *
     * `loadStatus` 는 스테이징 패널과 같은 UseCase 를 공유한다 — 중단 확인에 담을 "사라질 경로" 는
     * 스테이징이 보는 것과 같은 워킹트리 상태여야 한다.
     */
    val conflictActions = ConflictActions(
        loadFiles = LoadConflictedFilesUseCase(conflictGateway),
        loadContent = LoadConflictContentUseCase(conflictGateway),
        resolve = ResolveConflictUseCase(conflictGateway),
        continueAfterResolve = ContinueAfterResolveUseCase(mergeService),
        abort = AbortConflictedOperationUseCase(mergeService),
        loadStatus = stagingActions.loadStatus,
    )

    /**
     * 대화형 리베이스 계획 화면이 쓰는 동작 묶음.
     *
     * 계획 **편집**용 UseCase 는 없다 — 편집은 불변 `RebasePlan` 안에서 끝나고, 저장소는 적용
     * 시점에만 바뀐다.
     */
    val rebaseActions = RebaseActions(
        loadTargets = LoadRebaseTargetsUseCase(rebaseGateway),
        applyPlan = ApplyRebasePlanUseCase(rebaseGateway),
        loadProgress = LoadRebaseProgressUseCase(rebaseGateway),
    )

    val fetchRemote = FetchRemoteUseCase(remoteGateway)
    val pullRemote = PullRemoteUseCase(remoteGateway)
    val pushRemote = PushRemoteUseCase(remoteGateway)

    // ── 2차 UseCase (application) ──

    /** 환경설정 화면이 쓰는 세 동작. 서명 실효값은 저장소가 열려 있을 때만 읽힌다. */
    val loadPreferences = LoadPreferencesUseCase(settingsGateway)
    val updatePreferences = UpdatePreferencesUseCase(settingsGateway)
    val loadSigningPreferences = LoadSigningPreferencesUseCase(signingGateway)

    /** 설정 저장 경로 **밖**을 다루는 두 탭(계정·도구)의 의존. 단축키 탭의 레지스트리는 배선이 준다. */
    val identityUseCases = IdentityUseCases(
        loadProfiles = LoadProfilesUseCase(identityService),
        saveProfile = SaveProfileUseCase(identityService),
        deleteProfile = DeleteProfileUseCase(identityService),
        applyProfile = ApplyProfileUseCase(identityService),
        clearLocalIdentity = ClearLocalIdentityUseCase(identityService),
        assignedProfileName = AssignedProfileNameUseCase(identityGateway),
    )

    val externalToolUseCases = ExternalToolUseCases(
        openDiff = OpenDiffToolUseCase(externalToolGateway),
        openMerge = OpenMergeToolUseCase(externalToolGateway),
        checkAvailability = CheckToolAvailabilityUseCase(externalToolGateway),
    )

    /** Blame·파일 이력 화면이 쓰는 세 동작. 이력 간 비교는 기존 diff 경로를 그대로 쓴다. */
    val loadBlame = LoadBlameUseCase(blameGateway)
    val loadFileHistory = LoadFileHistoryUseCase(blameGateway)
    val compareFileHistory = CompareFileHistoryUseCase(diffGateway)

    /** gitlink 를 부모에 반영하는 경로. 기존 스테이징·커밋 계약을 그대로 쓴다 (결정 E6). */
    val commitSubmodulePointer = CommitSubmodulePointerUseCase(stagingGateway)

    /**
     * **활성 저장소 하나**가 쓰는 되돌리기 이력과 그 이력에 기록하는 실행 경로.
     *
     * 범위의 수명은 그 저장소다 — 저장소를 바꾸거나 닫으면 배선이 [newUndoScope] 로 새 범위를 만들고
     * 이전 이력은 버린다 (결정 G29). 한 범위 안에서는 `OperationRecorder` 를 생성자로 받는 네
     * 경로(graphops · reflog/bisect · submodule · worktree)가 [operationRecorder] 하나를 공유한다.
     * 탭별 범위와 전이 직렬화는 UND-81 소관이다.
     */
    inner class RepositoryUndoScope internal constructor() {

        private val undoStack = UndoStack()
        private val undoService = UndoService(undoStack, refGateway, repositoryGateway, worktreeOpsGateway)
        private val operationRecorder = OperationRecorder(refGateway, undoStack)

        /** Undo 버튼·이력 패널이 쓰는 네 동작. 모두 이 세션의 [undoStack] 하나를 본다. */
        val peekUndoTarget = PeekUndoTargetUseCase(undoService)
        val loadUndoHistory = LoadUndoHistoryUseCase(undoStack)
        val undoLastOperation = UndoLastOperationUseCase(undoService)
        val discardBlockedUndoEntry = DiscardBlockedUndoEntryUseCase(undoService)

        /** 서브모듈 패널의 동작 묶음. 변경 둘은 [operationRecorder] 를 받는다. */
        val submoduleActions = SubmodulePanelActions(
            load = LoadSubmodulesUseCase(submoduleGateway),
            initialize = InitializeSubmoduleUseCase(submoduleGateway, operationRecorder),
            update = UpdateSubmoduleUseCase(submoduleGateway, operationRecorder),
        )

        /** worktree 패널의 동작 묶음. 추가·제거가 [operationRecorder] 를 받는다. */
        val worktreeActions = WorktreePanelActions(
            load = LoadWorktreesUseCase(worktreeGateway),
            add = AddWorktreeUseCase(worktreeGateway, operationRecorder),
            remove = RemoveWorktreeUseCase(worktreeGateway, operationRecorder),
        )

        /** Reflog 복구·bisect 화면이 부르는 application 경계. 복구·판정 기록이 [operationRecorder] 로 간다. */
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

        /** 그래프 드래그·컨텍스트 메뉴·팔레트가 공유하는 조작 실행 경로. 기록은 [operationRecorder] 다. */
        val executeGraphOperation = ExecuteGraphOperationUseCase(worktreeOpsGateway, refGateway, operationRecorder)
    }

    /**
     * 활성 저장소가 쓸 Undo 범위를 새로 만든다.
     *
     * **범위를 앱 수명으로 두지 않는다.** 이력을 저장소 전환·닫기 뒤에도 들고 있으면, 같은 브랜치·
     * 같은 HEAD 인 clone 사이에서는 `baseline` 검사가 통과해 **이전 저장소에서 기록한 되돌리기가
     * 지금 저장소의 ref·워킹트리를 바꾼다** (결정 G29). 활성 저장소가 바뀔 때마다 배선이 이 함수로
     * 새 범위를 만들고, 버려진 범위의 이력은 되살아나지 않는다.
     */
    fun newUndoScope(): RepositoryUndoScope = RepositoryUndoScope()

    /**
     * 활성 저장소 상태를 읽는 조회 전용 경로다. 열기와 닫기는 welcome/명령 경로가 맡고, 배선은
     * 현재 핸들을 다시 열지 않는다.
     *
     * @throws UndineException.StateViolation 열린 저장소가 없을 때
     */
    suspend fun currentRepository(): OpenedRepository =
        gitAccess.withRepository { repository -> repository.toOpenedRepository() }

    /** 등록된 원격 이름. 툴바가 fetch·pull 대상을 정하는 재료다. */
    suspend fun listRemotes() = remoteGateway.listRemotes()

    /** 참조 목록. 그래프·검색이 어느 ref 를 훑을지와 참조 칩 색인의 재료다. */
    suspend fun listBranches() = refGateway.listBranches()

    suspend fun listTags() = refGateway.listTags()

    /**
     * 열린 저장소 핸들을 닫는다. 창을 닫을 때와 저장소를 바꿀 때 호출한다 —
     * 닫지 않으면 JGit 파일 핸들이 세션 동안 남는다.
     */
    suspend fun closeRepository() = repositoryGateway.close()
}
