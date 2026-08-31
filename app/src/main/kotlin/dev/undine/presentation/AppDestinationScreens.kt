// 함수 수는 복잡도가 아니라 **화면 목록**을 따른다 — 목적지 하나당 배선 컴포저블 하나다.
// 더 쪼개면 배선이 어느 파일에 있는지 찾는 비용만 늘고, 화면 구현 파일과의 경계는 그대로다.
@file:Suppress("TooManyFunctions")

package dev.undine.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.application.reflog.RecoveryActions
import dev.undine.di.AppComponent
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.blame.LineRange
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.RefMoveConfirmation
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.presentation.blame.BlameHistoryState
import dev.undine.presentation.blame.BlameHistoryView
import dev.undine.presentation.blame.BlameTarget
import dev.undine.presentation.commitdetail.CommitDetailPanel
import dev.undine.presentation.conflict.ConflictEditor
import dev.undine.presentation.conflict.ConflictState
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.diff.DiffViewer
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.preferences.PreferencesScreen
import dev.undine.presentation.preferences.PreferencesState
import dev.undine.presentation.preferences.PreferencesTabDependencies
import dev.undine.presentation.rebase.RebasePlanEditor
import dev.undine.presentation.rebase.RebasePlanState
import dev.undine.presentation.recovery.RecoveryMode
import dev.undine.presentation.recovery.RecoveryScreen
import dev.undine.presentation.recovery.RecoveryState
import dev.undine.presentation.recovery.rememberRecoveryState
import dev.undine.presentation.shell.AppShellState
import dev.undine.presentation.staging.StagingPanel
import dev.undine.presentation.staging.StagingState
import dev.undine.presentation.submodule.SubmodulePanelState
import dev.undine.presentation.submodule.SubmoduleWorktreePanel
import dev.undine.presentation.submodule.WorktreePanelState
import dev.undine.presentation.undo.UndoPanel
import dev.undine.presentation.welcome.WelcomeCloneEvents
import dev.undine.presentation.welcome.WelcomeEvents
import dev.undine.presentation.welcome.WelcomeScreen
import dev.undine.presentation.welcome.WelcomeState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.domain.DiffResult
import dev.undine.presentation.graph.CommitGraphView
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.search.SearchPanel
import dev.undine.presentation.shell.AppShell
import dev.undine.presentation.shell.AppShellSlots
import dev.undine.presentation.sidebar.SidebarTree
import dev.undine.presentation.toolbar.RemoteToolbar
import dev.undine.presentation.toolbar.rememberRemoteToolbarState
import java.time.Clock
import kotlinx.coroutines.launch

/**
 * Blame 을 처음 열 때 읽는 줄 수. blame 은 비싸서 보이는 구간만 먼저 읽고, 나머지는 스크롤이
 * 하단에 닿을 때 [BlameHistoryState] 가 이어 읽는다.
 */
private const val INITIAL_BLAME_LINES = 200

/** 중앙 영역에서 검색 패널과 커밋 그래프가 나눠 갖는 높이 비율 — 그래프가 주 표면이라 더 크다. */
private const val SEARCH_WEIGHT = 1f
private const val GRAPH_WEIGHT = 2f

/** 복구 브랜치 이름에 붙일 커밋 해시 길이. git 이 짧은 해시로 쓰는 길이와 같다. */
private const val SHORT_COMMIT_LENGTH = 7

@Composable
@Suppress("LongParameterList") // 배선 한 지점이 화면 전부에 값을 나눠 주는 자리다.
internal fun DestinationArea(
    destination: AppDestination,
    component: AppComponent,
    errors: AppErrorState,
    navigation: AppNavigationState,
    shellState: AppShellState,
    context: RepositoryContext,
    screens: RepositoryScreens,
    undo: ActiveRepositoryUndo,
    registry: CommandRegistry,
    welcomeState: WelcomeState,
    onOpenRepository: (RepositoryPath) -> Unit,
) {
    // 지금 그려진 목적지를 태그로 남긴다 — 배선이 고른 화면을 테스트가 화면 문구에 기대지 않고 집는다.
    Box(modifier = Modifier.fillMaxSize().testTag(AppDestinationTags.of(destination))) {
        when (destination) {
            AppDestination.WELCOME -> WelcomeArea(state = welcomeState)

            AppDestination.REPOSITORY -> RepositoryArea(
                component = component,
                errors = errors,
                shellState = shellState,
                context = context,
                screens = screens,
                tabsSlot = {},
            )

            AppDestination.PREFERENCES -> SecondaryScreen(navigation) {
                PreferencesArea(component = component, registry = registry)
            }

            AppDestination.BLAME -> SecondaryScreen(navigation) {
                BlameArea(component = component, shellState = shellState)
            }

            AppDestination.UNDO -> SecondaryScreen(navigation) {
                UndoPanel(state = undo.state, modifier = Modifier.fillMaxSize())
            }

            AppDestination.SUBMODULE_WORKTREE -> SecondaryScreen(navigation) {
                SubmoduleWorktreeArea(
                    component = component,
                    undoScope = undo.scope,
                    shellState = shellState,
                    onOpenRepository = onOpenRepository,
                    errors = errors,
                )
            }

            AppDestination.RECOVERY -> SecondaryScreen(navigation) {
                RecoveryArea(recovery = undo.scope.recoveryActions, context = context)
            }
        }
    }
}

/**
 * 2차 화면의 공통 껍데기 — 돌아가는 길 하나와 내용.
 *
 * 메뉴 없이도 저장소로 돌아올 수 있어야 한다. 나가는 길이 메뉴바뿐이면 메뉴를 찾지 못한 사용자는
 * 갇힌다.
 */
@Composable
private fun SecondaryScreen(navigation: AppNavigationState, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(UndineTokens.color.background)) {
        Row(modifier = Modifier.fillMaxWidth().padding(UndineTokens.spacing.small)) {
            UndineToolbarButton(
                label = "← ${AppDestination.REPOSITORY.label}",
                onClick = { navigation.go(AppDestination.REPOSITORY) },
            )
        }
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** 환영 화면. 상태 홀더의 동작을 화면 이벤트로 옮기기만 한다. */
@Composable
private fun WelcomeArea(state: WelcomeState) {
    WelcomeScreen(
        state = state.screenState,
        events = WelcomeEvents(
            onOpenRecent = state::open,
            onForgetRecent = state::forget,
            onChooseLocalDirectory = { chooseDirectory()?.let(state::open) },
            clone = WelcomeCloneEvents(
                onUrlChange = state::changeCloneUrl,
                onTargetChange = state::changeCloneTarget,
                onStart = state::startClone,
                onCancel = state::cancelClone,
            ),
            onDismissNotice = state::dismissNotice,
        ),
        modifier = Modifier.fillMaxSize(),
    )
    LaunchedEffect(Unit) { state.refresh() }
}

/**
 * 환경설정 화면.
 *
 * 단축키 탭이 쓰는 레지스트리는 **팔레트가 쓰는 그것과 같은 인스턴스**다 — 다른 것을 주면 탭에서
 * 바꾼 단축키가 실제 실행 경로에 닿지 않는다.
 */
@Composable
private fun PreferencesArea(component: AppComponent, registry: CommandRegistry) {
    val scope = rememberCoroutineScope()
    val state = remember(component, scope) {
        PreferencesState(
            scope = scope,
            loadPreferences = component.loadPreferences,
            updatePreferences = component.updatePreferences,
            loadSigningPreferences = component.loadSigningPreferences,
        )
    }
    val dependencies = remember(component, registry) {
        PreferencesTabDependencies(
            identity = component.identityUseCases,
            externalTools = component.externalToolUseCases,
            commands = registry,
        )
    }
    LaunchedEffect(state) { state.refresh() }
    PreferencesScreen(state = state, dependencies = dependencies, modifier = Modifier.fillMaxSize())
}

/**
 * Blame·파일 이력 화면.
 *
 * 대상은 셸이 고른 파일이다 — 고른 파일이 없으면 blame 할 것이 없으므로 무엇을 골라야 하는지 알린다.
 */
@Composable
private fun BlameArea(component: AppComponent, shellState: AppShellState) {
    val filePath = shellState.selection.filePath
    val state = remember(component) {
        BlameHistoryState(component.loadBlame, component.loadFileHistory, component.compareFileHistory)
    }
    when (filePath) {
        null -> BasicText(
            text = "Blame 을 볼 파일을 커밋 상세에서 먼저 고르세요.",
            style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundSecondary),
            modifier = Modifier.padding(UndineTokens.spacing.medium),
        )

        else -> BlameHistoryView(
            target = BlameTarget(filePath, LineRange.of(1, INITIAL_BLAME_LINES)),
            state = state,
            onCommitSelected = { commit -> shellState.selectCommit(commit.id) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 서브모듈·worktree 패널.
 *
 * 열기 요청은 활성 저장소를 바꾼다. 다중 탭 전환은 UND-81 이 맡는다.
 * 부모 커밋 메시지는 배선이 정한다. 화면이 메시지를 묻지 않으므로 여기서 주지 않으면 그 동작은
 * 아무 일도 하지 않는 버튼이 된다.
 */
@Composable
@Suppress("LongParameterList") // 배선 한 지점이 패널 둘에 값을 나눠 주는 자리다.
private fun SubmoduleWorktreeArea(
    component: AppComponent,
    undoScope: AppComponent.RepositoryUndoScope,
    shellState: AppShellState,
    onOpenRepository: (RepositoryPath) -> Unit,
    errors: AppErrorState,
) {
    val scope = rememberCoroutineScope()
    val repositoryPath = shellState.selection.repository ?: return
    val submodules = remember(repositoryPath, undoScope) {
        SubmodulePanelState(
            actions = undoScope.submoduleActions,
            scope = scope,
            onOpenRequested = { path -> onOpenRepository(RepositoryPath("${repositoryPath.value}/$path")) },
            onCommitToParentRequested = { path ->
                scope.launch {
                    try {
                        component.commitSubmodulePointer.execute(path, "서브모듈 $path 포인터를 갱신합니다")
                    } catch (failure: UndineException) {
                        errors.report(failure, logPath = null)
                    }
                }
            },
        )
    }
    val worktrees = remember(repositoryPath, undoScope) {
        WorktreePanelState(
            actions = undoScope.worktreeActions,
            currentWorktree = repositoryPath,
            scope = scope,
            onOpenRequested = onOpenRepository,
        )
    }
    LaunchedEffect(repositoryPath) {
        submodules.refresh()
        worktrees.refresh()
    }
    SubmoduleWorktreePanel(submodules = submodules, worktrees = worktrees, modifier = Modifier.fillMaxSize())
}

/**
 * Reflog 복구·bisect 화면.
 *
 * 복구 대상은 **배선이 정한다** (`RecoveryScreen` KDoc). 새 브랜치는 짧은 해시로 이름을 만들고,
 * 기존 ref 이동은 **현재 브랜치**를 옮긴다 — 화면이 경고로 "밀려난다" 고 말한 커밋이 바로 지금
 * 현재 브랜치가 가리키는 커밋이므로, 확인값도 그 값으로 만든다.
 */
@Composable
private fun RecoveryArea(recovery: RecoveryActions, context: RepositoryContext) {
    val state = rememberRecoveryState(recovery)
    LaunchedEffect(state) { state.load() }
    RecoveryScreen(
        state = state,
        modifier = Modifier.fillMaxSize(),
        onRecover = { entry, mode -> state.recoverWith(entry, mode, context.currentBranch) },
    )
}

/** 화면이 고른 모드를 실제 복구 대상으로 옮긴다. 옮길 ref 가 없으면 아무것도 하지 않는다. */
private fun RecoveryState.recoverWith(entry: ReflogEntry, mode: RecoveryMode, currentBranch: Branch?) {
    val target = when (mode) {
        RecoveryMode.NewBranch ->
            RecoveryTarget.NewBranch(RefName("recovered/${entry.to.value.take(SHORT_COMMIT_LENGTH)}"))

        RecoveryMode.MoveExisting -> currentBranch?.let { branch ->
            RecoveryTarget.MoveExisting(branch.name, RefMoveConfirmation.ofDisplacedCommit(branch.target))
        }
    }
    target?.let(::recoverSelected)
}


/**
 * 고른 파일의 diff 를 읽는다. 고른 것이 없으면 읽을 것도 없다.
 *
 * **읽지 못한 diff 를 "변경 없음" 으로 내려보내지 않는다** — 상세 패널이 그 자리를 대신 채우면
 * 사용자는 파일이 안 바뀐 줄로 안다. 실패는 전역 안내로 올리고 `null` 을 돌려준다.
 *
 * **취소는 잡지 않는다.** 커밋·파일 선택이 바뀌면 앞선 읽기의 스코프가 취소되는데, 그걸 실패로
 * 보고하면 사용자가 화면을 넘길 때마다 오류가 뜬다 (`exception-handling` 취소 전파).
 *
 * 읽기를 [load] 로 받는 이유는 이 판단(오류 보고·`null` 처리·취소 전파)이 Composable 밖에서
 * 검증돼야 하기 때문이다 — 레포에 Compose UI 테스트 런타임이 없다.
 */
internal suspend fun loadSelectedFileDiff(
    commit: CommitId?,
    filePath: String?,
    errors: AppErrorState,
    load: suspend (CommitId, String) -> DiffResult,
): DiffResult? {
    if (commit == null || filePath == null) return null
    return try {
        load(commit, filePath)
    } catch (failure: UndineException) {
        errors.report(failure, logPath = null)
        null
    }
}

/**
 * 저장소를 연 뒤의 메인 화면.
 *
 * 선택 흐름은 한 방향이다: 그래프·검색이 커밋을 고르면 셸 상태가 바뀌고, 상세 패널이 그 커밋의
 * 파일을 내고, 파일을 고르면 diff 가 그 파일을 읽는다. 아래로만 흐르므로 어느 화면도 다른 화면의
 * 상태를 직접 만지지 않는다.
 */
@Composable
@Suppress("LongMethod", "LongParameterList") // 배선은 조립 순서를 한눈에 보여야 한다 — 쪼개면 흐름이 흩어진다.
private fun RepositoryArea(
    component: AppComponent,
    errors: AppErrorState,
    shellState: AppShellState,
    context: RepositoryContext,
    screens: RepositoryScreens,
    tabsSlot: @Composable () -> Unit,
) {
    val selection = shellState.selection
    val refIndex = remember(context) {
        CommitRefIndex.of(context.branches, context.tags, context.opened?.currentBranch)
    }
    val toolbarState = rememberRemoteToolbarState(
        fetchRemote = component.fetchRemote,
        pullRemote = component.pullRemote,
        pushRemote = component.pushRemote,
        remotes = context.remotes,
        branch = context.currentBranch,
    )

    // 고른 커밋의 변경 파일. 커밋이 바뀌면 다시 읽는다.
    LaunchedEffect(selection.commit) {
        selection.commit?.let { commit -> screens.detail.load(commit, parentIndex = 0) }
    }

    // 고른 파일의 diff. 파일이나 커밋이 바뀌면 다시 읽는다.
    var fileDiff by remember { mutableStateOf<DiffResult?>(null) }
    LaunchedEffect(selection.commit, selection.filePath) {
        fileDiff = loadSelectedFileDiff(selection.commit, selection.filePath, errors) { commit, filePath ->
            component.loadFileDiff.execute(commit, filePath)
        }
    }

    AppShell(
        state = shellState,
        slots = AppShellSlots(
            toolbar = { RemoteToolbar(state = toolbarState) },
            tabs = tabsSlot,
            sidebar = {
                SidebarTree(
                    state = screens.sidebar,
                    opened = context.opened,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            center = {
                // **중앙 높이를 배선이 나눈다.** 검색 패널은 자기 루트가 `fillMaxSize` 라 주는 만큼을
                // 다 쓴다 — 나누지 않으면 패널이 중앙을 통째로 먹고 그래프가 높이 0 으로 그려진다
                // (사용자에게는 커밋 그래프가 아예 없는 화면이 된다). 그래프가 이 화면의 주 표면이라
                // 더 큰 몫을 준다.
                Column(modifier = Modifier.fillMaxSize()) {
                    SearchPanel(
                        state = screens.search,
                        modifier = Modifier.fillMaxWidth().weight(SEARCH_WEIGHT),
                        onCommitSelected = { commit -> shellState.selectCommit(commit.id) },
                    )
                    CommitGraphView(
                        state = screens.graph,
                        now = Clock.systemDefaultZone().instant(),
                        modifier = Modifier.fillMaxWidth().weight(GRAPH_WEIGHT),
                        refIndex = refIndex,
                        dragDropState = screens.dragDrop,
                        onCommitSelected = { commit -> shellState.selectCommit(commit.id) },
                    )
                }
            },
            bottom = {
                BottomArea(
                    inspection = CommitInspection(
                        commit = screens.graph.selectedCommit,
                        detailState = screens.detail,
                        diffState = screens.diff,
                        fileDiff = fileDiff,
                        filePath = selection.filePath,
                    ),
                    stagingState = screens.staging,
                    conflictState = screens.conflict,
                    rebaseState = screens.rebase,
                    onSelectFile = { filePath -> shellState.selectFile(filePath) },
                )
            },
        ),
    )
}

/**
 * 아래 영역.
 *
 * **충돌이 남아 있으면 충돌 에디터가 다른 무엇보다 앞선다** — 충돌을 해결하기 전에는 커밋도
 * 브랜치 이동도 할 수 없으므로, 지금 해야 하는 유일한 일을 보여준다.
 *
 * 그다음은 **열어 둔 리베이스 계획**이다. 계획은 사용자가 명시적으로 연 것이고 적용하거나 취소할
 * 때까지 편집 중인 작업이므로, 커밋을 훑는 화면이 그것을 덮지 않는다.
 *
 * 둘 다 없고 커밋을 고르지 않았으면 **작업 중인 변경**(스테이징 패널)이다 — Git 클라이언트를 여는
 * 이유가 대개 그것이고, 빈 화면보다 지금 할 일을 보여주는 것이 맞다. 커밋을 고르면 그 커밋의
 * 상세로, 파일까지 고르면 그 파일의 diff 로 바뀐다.
 */
@Composable
private fun BottomArea(
    inspection: CommitInspection,
    stagingState: StagingState,
    conflictState: ConflictState,
    rebaseState: RebasePlanState,
    onSelectFile: (String) -> Unit,
) {
    val selectedCommit = inspection.commit
    val fileDiff = inspection.fileDiff
    when {
        !conflictState.isClean ->
            ConflictEditor(state = conflictState, modifier = Modifier.fillMaxSize())

        rebaseState.plan != null ->
            RebasePlanEditor(state = rebaseState, modifier = Modifier.fillMaxSize())

        selectedCommit == null ->
            StagingPanel(state = stagingState, modifier = Modifier.fillMaxSize())

        fileDiff == null -> CommitDetailPanel(
            commit = selectedCommit,
            state = inspection.detailState,
            modifier = Modifier.fillMaxSize(),
            onSelectFile = onSelectFile,
        )

        else -> DiffViewer(
            result = fileDiff,
            state = inspection.diffState,
            // 인덱스 상태의 단일 소유자는 패널이다 — 뷰어는 의사만 올린다.
            onStageHunk = { hunk ->
                stagingState.stageHunk(inspection.filePath.orEmpty(), listOf(hunk))
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
