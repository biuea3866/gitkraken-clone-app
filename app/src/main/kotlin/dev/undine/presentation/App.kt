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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.undine.BuildInfo
import dev.undine.di.AppComponent
import dev.undine.domain.Branch
import dev.undine.domain.BranchTarget
import dev.undine.domain.Commit
import dev.undine.domain.DiffResult
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.Tag
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.domain.blame.LineRange
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.reflog.RefMoveConfirmation
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.RefName
import dev.undine.presentation.blame.BlameHistoryState
import dev.undine.presentation.blame.BlameHistoryView
import dev.undine.presentation.blame.BlameTarget
import dev.undine.presentation.commitdetail.CommitDetailPanel
import dev.undine.presentation.commitdetail.CommitDetailState
import dev.undine.presentation.commitdetail.rememberCommitDetailState
import dev.undine.presentation.conflict.ConflictEditor
import dev.undine.presentation.conflict.ConflictState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.diff.DiffViewer
import dev.undine.presentation.diff.DiffViewerState
import dev.undine.presentation.diff.rememberDiffViewerState
import dev.undine.presentation.graph.CommitGraphView
import dev.undine.presentation.graph.CommitRefIndex
import dev.undine.presentation.graph.GraphDragDropState
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.graph.GraphViewState
import dev.undine.presentation.graph.rememberGraphViewState
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.systemStrings
import dev.undine.presentation.i18n.tabs
import dev.undine.presentation.palette.CommandPalette
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.commandShortcuts
import dev.undine.presentation.palette.rememberCommandCenter
import dev.undine.presentation.palette.toShortcutOverrides
import dev.undine.presentation.preferences.PreferencesScreen
import dev.undine.presentation.preferences.PreferencesState
import dev.undine.presentation.preferences.PreferencesTabDependencies
import dev.undine.presentation.rebase.RebasePlanEditor
import dev.undine.presentation.rebase.RebasePlanState
import dev.undine.presentation.recovery.RecoveryMode
import dev.undine.presentation.recovery.RecoveryScreen
import dev.undine.presentation.recovery.RecoveryState
import dev.undine.presentation.recovery.rememberRecoveryState
import dev.undine.presentation.search.SearchPanel
import dev.undine.presentation.search.SearchState
import dev.undine.presentation.search.rememberSearchState
import dev.undine.presentation.shell.AppShell
import dev.undine.presentation.shell.AppShellState
import dev.undine.presentation.shell.AppShellSlots
import dev.undine.presentation.shell.rememberAppShellState
import dev.undine.presentation.staging.StagingPanel
import dev.undine.presentation.staging.StagingState
import dev.undine.presentation.sidebar.SidebarState
import dev.undine.presentation.sidebar.SidebarTree
import dev.undine.presentation.submodule.SubmodulePanelState
import dev.undine.presentation.submodule.SubmoduleWorktreePanel
import dev.undine.presentation.submodule.WorktreePanelState
import dev.undine.presentation.tabs.RepositoryTabs
import dev.undine.presentation.toolbar.RemoteToolbar
import dev.undine.presentation.toolbar.rememberRemoteToolbarState
import dev.undine.presentation.undo.UndoPanel
import dev.undine.presentation.undo.UndoState
import dev.undine.presentation.welcome.WelcomeCloneEvents
import dev.undine.presentation.welcome.WelcomeEvents
import dev.undine.presentation.welcome.WelcomeScreen
import dev.undine.presentation.welcome.WelcomeState
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/** 창 제목에 버전을 붙인다 — 사용자가 어느 빌드를 쓰는지 물어볼 때 답할 데가 필요하다. */
private val windowTitle: String = "Undine ${BuildInfo.VERSION}"

/**
 * 저장소를 연 뒤 알게 되는 것들의 스냅샷. 함께 읽고 함께 바뀌므로 한 값으로 묶는다 —
 * 셋을 따로 넘기면 어느 하나만 갱신된 중간 상태가 화면에 도달할 수 있다.
 */
@Immutable
internal data class RepositoryContext(
    val opened: OpenedRepository? = null,
    val branches: List<Branch> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val remotes: List<String> = emptyList(),
) {
    val currentBranch: Branch? get() = branches.firstOrNull { it.isCurrent }
}

/**
 * 고른 커밋을 들여다보는 데 필요한 값 묶음. 커밋·파일 선택이 바뀌면 함께 바뀌므로 하나로 묶는다.
 */
@Immutable
internal data class CommitInspection(
    val commit: Commit?,
    val detailState: CommitDetailState,
    val diffState: DiffViewerState,
    val fileDiff: DiffResult?,
    val filePath: String?,
)

/**
 * 열린 저장소 화면이 쓰는 상태 홀더 묶음.
 *
 * **한 곳에서 만들어 아래로만 내려보낸다.** 커맨드·메뉴가 부르는 동작(참조 새로 읽기·diff 전환·
 * 리베이스 계획 열기)이 이 홀더들에 있는데, 등록은 앱 시작 시 한 번뿐이라 등록 시점에 최신 홀더를
 * 붙잡을 수 없다. 그래서 홀더를 셸 안쪽이 아니라 여기서 만들고, 커맨드는
 * [rememberUpdatedState] 를 거쳐 **지금의** 홀더를 읽는다.
 */
@Stable
@Suppress("LongParameterList") // 조립 결과를 그대로 나르는 값 묶음이다 — 쪼개면 배선이 더 길어진다.
internal class RepositoryScreens(
    val graph: GraphViewState,
    val search: SearchState,
    val detail: CommitDetailState,
    val diff: DiffViewerState,
    val staging: StagingState,
    val conflict: ConflictState,
    val rebase: RebasePlanState,
    val sidebar: SidebarState,
    val dragDrop: GraphDragDropState,
)

/** 설정·로그를 두는 사용자 디렉터리. 앱 전용 하위 디렉터리 하나만 쓴다. */
private fun appDirectory(): Path =
    Paths.get(System.getProperty("user.home"), ".undine")

/**
 * 조립이 만든 것 중 **밖에서 확인해야 하는** 홀더들.
 *
 * 화면 전환·명령 등록·그래프 조작 홀더는 모두 [AppContent] 안에서 만들어진다 — 등록이
 * 앱 시작 시 한 번뿐이어야 하고 홀더가 앱 수명을 가져야 하기 때문이다. 그 대가로 조립을 그대로
 * 띄운 채 화면을 넘겨 보거나 등록 결과를 확인할 길이 사라지는데, 이 묶음이 그 통로다.
 */
@Stable
internal class AppWiring(
    val navigation: AppNavigationState,
    val registry: CommandRegistry,
    val dragDrop: GraphDragDropState,
    /**
     * **지금의** 되돌리기 배선. 활성 저장소가 바뀌면 다른 범위가 되므로 값이 아니라 통로로 둔다 —
     * 조립 시점의 범위를 붙잡으면 전환 뒤의 이력을 확인할 수 없다 (결정 G29).
     */
    val currentUndo: () -> ActiveRepositoryUndo,
    /**
     * **지금** 화면이 쓰는 저장소 컨텍스트. 값이 아니라 통로인 이유는 [currentUndo] 와 같다 —
     * 조립 시점의 값을 붙잡으면 전이 뒤에 컨텍스트가 어느 저장소를 가리키는지 확인할 수 없다.
     */
    val currentContext: () -> RepositoryContext,
)

/**
 * 활성 저장소의 되돌리기 배선 — [AppComponent.RepositoryUndoScope] 와 그 범위를 읽는 화면 상태.
 */
@Stable
internal class ActiveRepositoryUndo(
    val scope: AppComponent.RepositoryUndoScope,
    val state: UndoState,
)

/**
 * 앱 전체 배선.
 *
 * 화면은 [AppDestination] 하나로 갈린다 — 저장소 셸 3분할, 그리고 2차 기능의 화면 다섯. 슬롯에
 * 꽂히는 컴포넌트는 셸이 알지 못하고(`AppShellSlots`), 선택 흐름(커밋 → 상세 → diff)은 여기서
 * 단방향으로 잇는다.
 *
 * **화면은 UseCase 만 받는다** — Gateway 는 [AppComponent] 안에 갇혀 있다.
 *
 * @param windowScope OS 메뉴바를 붙일 창. 창 없이 화면만 조합할 때는 `null` 이며 메뉴바가 없다.
 */
@Composable
fun App(
    component: AppComponent,
    errors: AppErrorState,
    modifier: Modifier = Modifier,
    windowScope: FrameWindowScope? = null,
) {
    AppRoot(component = component, errors = errors, modifier = modifier, windowScope = windowScope)
}

/**
 * 테마·문자열을 두른 앱 본체.
 *
 * @param onAssembled 조립이 끝나면 [AppWiring] 을 한 번 넘긴다. 앱 실행 경로는 넘기지 않으며,
 *   **실제 조립 경로를 띄운 채** 화면 전환·등록 결과를 확인해야 하는 테스트가 쓰는 통로다.
 *   조립이 만든 홀더를 밖에서 다시 만들면 검증 대상이 앱이 아니라 그 복제본이 된다.
 */
@Composable
internal fun AppRoot(
    component: AppComponent,
    errors: AppErrorState,
    modifier: Modifier = Modifier,
    windowScope: FrameWindowScope? = null,
    onAssembled: ((AppWiring) -> Unit)? = null,
) {
    // 기본은 다크다. Git 클라이언트는 이력·diff 를 오래 들여다보는 화면이고, 설정 화면이 저장한
    // 테마를 읽어 오기 전까지 기본값이 곧 유일한 선택이다.
    UndineTheme(themeMode = ThemeMode.DARK) {
        CompositionLocalProvider(LocalStrings provides systemStrings()) {
            Box(modifier = modifier.fillMaxSize().background(UndineTokens.color.background)) {
                AppContent(
                    component = component,
                    errors = errors,
                    windowScope = windowScope,
                    onAssembled = onAssembled,
                )
                errors.failure?.let { failure ->
                    GlobalFailureBanner(failure = failure, onDismiss = errors::dismiss)
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // 배선은 조립 순서를 한눈에 보여야 한다 — 쪼개면 흐름이 흩어진다.
private fun AppContent(
    component: AppComponent,
    errors: AppErrorState,
    windowScope: FrameWindowScope?,
    onAssembled: ((AppWiring) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val shellState = rememberAppShellState()
    val navigation = remember { AppNavigationState() }
    val selection = shellState.selection

    // 저장소를 열면 채워지는 컨텍스트. 단일 활성 저장소가 바뀌면 이전 참조를 함께 비운다.
    var context by remember { mutableStateOf(RepositoryContext()) }
    val latestContext = rememberUpdatedState(context)

    // 탭 세션 배선. 셸 선택·되돌리기 범위·탭 막대가 **이 하나**를 통해 같은 전이를 본다 (UND-81).
    val sessions = remember(component, shellState) {
        RepositorySessionDriver(
            sessions = component.repositorySession,
            createUndoScope = component::newUndoScope,
            onActiveRepository = { path ->
                shellState.selectRepository(path)
                // **컨텍스트도 같은 임계 구역에서 내려놓는다** (결정 G42). 아래 효과가 새 저장소의
                // ref 를 채울 때까지 이전 저장소의 브랜치·태그·원격을 들고 있으면, 탭·셸 선택은 이미
                // 옮겨 간 그 창에서 누른 조작이 **다른 저장소**로 간다. 채우기까지 구역 안에서 할 수는
                // 없다 — 조회도 같은 Git 경계를 지나므로 구역 안에서 부르면 교착한다. 그래서 "지나간
                // 저장소를 가리키지 않는 것" 을 구역이 보장하고, 채우기는 밖에서 하되 늦게 도착한
                // 결과는 버린다.
                context = RepositoryContext()
            },
        )
    }
    // 저장해 둔 탭을 시작 시 되돌린다. 실패해도 앱은 뜬다 — 복원 실패로 시작 화면까지 막지 않는다.
    LaunchedEffect(sessions) {
        try {
            sessions.restore()
        } catch (failure: UndineException) {
            errors.report(failure, logPath = null)
        } catch (failure: IOException) {
            errors.report(failure, logPath = null)
        }
    }

    val welcomeState = remember(component, sessions) {
        WelcomeState(
            actions = component.welcomeActions,
            scope = scope,
            // 연 저장소는 **탭으로** 들어간다. 셸 선택은 탭 전이가 정한다 — 여기서 따로 정하면
            // 탭 장부와 화면이 가리키는 저장소가 갈린다.
            //
            // 전이 실패는 **삼키지 않는다.** 최근 목록에서 열기도 clone 완료도 이 한 콜백으로
            // 들어오는데, 맨 `launch` 로 띄우면 세션 전이가 실패했을 때 탭이 열리지 않은 이유가
            // 사용자에게 닿지 않는다 — 탭 막대의 요청과 같은 경로로 전역 안내에 올린다.
            onRepositoryOpened = { path -> scope.reportingFailure(errors) { sessions.open(path) } },
        )
    }

    // 되돌리기 범위는 **활성 탭 저장소의 것**이다. 탭을 오가도 버리지 않고, 마지막 참조 탭이 닫힐 때
    // 사라진다 — 판단 기준은 baseline(브랜치·HEAD)이 아니라 세션 키다. baseline 은 같은 커밋을
    // 가리키는 clone 둘에서 같아 구분에 쓸 수 없다 (결정 G29, UND-81).
    val undo = rememberActiveUndo(sessions.activeUndoScope, scope)
    val latestUndo = rememberUpdatedState(undo)

    // 그래프 조작 홀더는 앱 수명이다. 팔레트 명령도 시작 시 한 번 등록되므로 화면과 같은 홀더를 쓴다.
    // 홀더는 그대로 두고 **실행 경로만** 지금의 범위를 읽는다 — 저장소마다 홀더를 다시 만들면 시작 시
    // 등록한 명령이 옛 홀더를 붙잡아 확인창이 화면에 닿지 않는다.
    val dragDrop = remember(component, scope) {
        GraphDragDropState(
            execute = { operation -> latestUndo.value.scope.executeGraphOperation.execute(operation) },
            scope = scope,
        )
    }
    val graphCallbacks = remember(dragDrop) { GraphOperationCallbacks(dragDrop) }
    // 저장소가 바뀌면 열려 있던 확인창을 접는다 — 이전 저장소의 ref 를 대상으로 한 확인이다.
    LaunchedEffect(selection.repository) { dragDrop.cancelConfirmation() }

    // 탭 전이가 비워 둔 컨텍스트를 **활성 탭 저장소의 값으로** 채운다. 다시 열면 활성 핸들을
    // 불필요하게 교체하므로 조회만 한다. 실패를 빈 값으로 위장하지 않고 전역 안내로 보낸다.
    //
    // 조회 중 사용자가 또 다른 탭으로 옮겨 갔으면 **이 결과를 버린다.** 늦게 도착한 값을 그대로
    // 얹으면 컨텍스트만 지나간 저장소로 되돌아간다 — 전이가 구역 안에서 맞춰 놓은 것을 구역 밖의
    // 조회가 다시 어긋내는 것이다 (결정 G42). 판정과 반영 사이에 중단점이 없어야 이 검사가 성립한다.
    //
    // 비교 대상은 **지금의 `shellState.selection`** 이다. 컴포지션이 캡처한 지역 `selection` 과
    // 대조하면 같은 스냅샷끼리 비교해 **항상 참**이라 아무것도 걸러 내지 못한다. 키가 바뀔 때
    // `LaunchedEffect` 가 이 코루틴을 취소하지만, 마지막 정지 지점 이후에는 취소가 관측되지 않으므로
    // 취소만으로는 이 창이 닫히지 않는다.
    LaunchedEffect(selection.repository) {
        val target = selection.repository
        if (target == null) {
            context = RepositoryContext()
            return@LaunchedEffect
        }
        val loaded = try {
            RepositoryContext(
                opened = component.currentRepository(),
                branches = component.listBranches(),
                tags = component.listTags(),
                remotes = component.listRemotes(),
            )
        } catch (failure: UndineException) {
            errors.report(failure, logPath = null)
            RepositoryContext()
        } catch (failure: IOException) {
            errors.report(failure, logPath = null)
            RepositoryContext()
        }
        if (shellState.selection.repository == target) context = loaded
    }

    val screens = rememberRepositoryScreens(component, undo.scope, shellState, latestContext, errors, dragDrop)
    val latestScreens = rememberUpdatedState(screens)

    // 팔레트 열기 요청. 커맨드 action 은 non-composable 이라 여기서 신호만 세우고 열기는 효과가 한다.
    var paletteRequested by remember { mutableStateOf(false) }

    // **등록은 앱 시작 시 한 번뿐이다.** 저장소마다 다시 등록하면 같은 id 가 두 번 들어와 거부되고,
    // 무엇보다 단축키 충돌이 첫 저장소를 열 때까지 숨는다.
    val registry = remember {
        CommandRegistry().also { target ->
            registerAppCommands(
                registry = target,
                handlers = AppCommandHandlers(
                    onOpenPalette = { paletteRequested = true },
                    // 닫기는 **활성 탭**을 닫는다. 전 세션을 닫으면 다른 탭의 열린 핸들까지 함께
                    // 사라져, 사용자가 닫지 않은 저장소의 이력과 화면이 같이 무너진다.
                    onCloseRepository = { scope.reportingFailure(errors) { sessions.closeActive() } },
                    onRefreshRefs = { latestScreens.value.sidebar.refresh() },
                    onToggleDiffView = { latestScreens.value.diff.toggleViewMode() },
                    onOpenRebasePlan = { latestScreens.value.rebase.load() },
                ),
                )
            registerSecondaryCommands(
                registry = target,
                handlers = SecondaryCommandHandlers(
                    onNavigate = navigation::go,
                    onOpenRepository = { chooseDirectory()?.let(welcomeState::open) },
                    onUndoLast = { latestUndo.value.state.undoFromKeyboard() },
                    availabilityOf = { destination ->
                        availabilityOf(destination, shellState.selection.repository != null)
                    },
                ),
                graphCallbacks = graphCallbacks,
                // 드래그가 없을 때 선택만으로 만들 수 있는 조작은 하나다 — 고른 커밋을 현재 브랜치에
                // 얹는 것. 나머지 넷은 출발 ref 가 필요해 드래그·컨텍스트 메뉴가 제공한다.
                selectedGraphOperation = {
                    shellState.selection.commit?.let { GraphOperation.CherryPick(it, BranchTarget.Current) }
                },
            )
        }
    }
    val commandCenter = rememberCommandCenter(registry)

    // 조립 결과를 한 번만 밖으로 알린다. 콜백은 리컴포지션마다 새 람다일 수 있으므로 효과의 키로
    // 쓰지 않는다 — 키로 쓰면 같은 조립을 매번 다시 알린다.
    val wiring = remember(navigation, registry, dragDrop) {
        AppWiring(
            navigation = navigation,
            registry = registry,
            dragDrop = dragDrop,
            currentUndo = { latestUndo.value },
            currentContext = { latestContext.value },
        )
    }
    val latestOnAssembled = rememberUpdatedState(onAssembled)
    LaunchedEffect(wiring) { latestOnAssembled.value?.invoke(wiring) }

    LaunchedEffect(paletteRequested) {
        if (paletteRequested) {
            commandCenter.paletteState.open()
            paletteRequested = false
        }
    }

    // 저장된 단축키 오버라이드를 **시작 시** 얹는다. 묶지 못한 id 는 설정의 단축키 탭이 목록과 경고로
    // 보여 준다 (결정 G20) — 여기서 대화상자를 띄우지 않는다. 설정을 읽지 못한 것은 다른 문제라
    // 조용히 넘기지 않고 전역 안내로 올린다.
    LaunchedEffect(registry) {
        try {
            registry.applyShortcutOverrides(component.loadPreferences.execute().shortcutOverrides.toShortcutOverrides())
        } catch (failure: IOException) {
            errors.report(failure, logPath = null)
        }
    }

    windowScope?.let { window ->
        with(window) {
            AppMenuBar(
                navigation = navigation,
                repositoryOpen = selection.repository != null,
                onOpenRepository = { chooseDirectory()?.let(welcomeState::open) },
                onUndoLast = { latestUndo.value.state.undo() },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusTarget()
            .commandShortcuts(commandCenter.shortcutHandler),
    ) {
        DestinationArea(
            destination = destinationFor(navigation.destination, selection.repository != null),
            component = component,
            errors = errors,
            navigation = navigation,
            shellState = shellState,
            context = context,
            screens = screens,
            undo = undo,
            registry = registry,
            welcomeState = welcomeState,
            onOpenRepository = welcomeState::open,
            tabsSlot = { RepositoryTabsArea(sessions = sessions, scope = scope, errors = errors) },
        )
        // **열렸을 때만 그린다.** 팔레트 컴포저블은 열림 여부를 스스로 판단하지 않는다
        // (`CommandPalette` KDoc). 늘 그리면 명령 목록이 화면 위에 덮인 채 남고, 그 아래
        // 그래프·패널로 가는 클릭까지 가로챈다.
        if (commandCenter.paletteState.isOpen) {
            CommandPalette(state = commandCenter.paletteState)
        }
    }
}

/**
 * 활성 저장소의 되돌리기 배선을 만든다.
 *
 * **[undoScope] 가 키다.** 어느 범위를 쓸지는 탭 배선([RepositorySessionDriver])이 저장소 세션 키로
 * 정하고, 여기서는 그 범위를 읽는 화면 상태만 붙인다 — 저장소 정체성 판단을 두 곳에 두면 화면이
 * 보는 이력과 기록이 쌓이는 이력이 갈린다 (결정 G29).
 */
@Composable
private fun rememberActiveUndo(
    undoScope: AppComponent.RepositoryUndoScope,
    coroutineScope: CoroutineScope,
): ActiveRepositoryUndo {
    val active = remember(undoScope, coroutineScope) {
        ActiveRepositoryUndo(
            scope = undoScope,
            state = UndoState(
                scope = coroutineScope,
                peekUndoTarget = undoScope.peekUndoTarget,
                loadUndoHistory = undoScope.loadUndoHistory,
                undoLastOperation = undoScope.undoLastOperation,
                discardBlockedUndoEntry = undoScope.discardBlockedUndoEntry,
            ),
        )
    }
    LaunchedEffect(active) { active.state.refresh() }
    return active
}

/**
 * 열린 저장소 화면의 상태 홀더를 만든다.
 *
 * 저장소가 바뀌면 홀더도 새로 만든다 — 이전 저장소의 스테이징·충돌 상태가 남으면 없는 파일을 보여준다.
 * 그래프·검색은 훑는 참조가 바뀌므로 참조 목록이 키다.
 */
@Composable
@Suppress("LongParameterList") // 배선 한 지점이 화면 홀더 전부에 값을 나눠 주는 자리다.
private fun rememberRepositoryScreens(
    component: AppComponent,
    undoScope: AppComponent.RepositoryUndoScope,
    shellState: AppShellState,
    context: State<RepositoryContext>,
    errors: AppErrorState,
    dragDrop: GraphDragDropState,
): RepositoryScreens {
    val scope = rememberCoroutineScope()
    val repositoryPath = shellState.selection.repository
    val current = context.value
    val refs = remember(current) { listOfNotNull(current.opened?.currentBranch) }

    val graph = rememberGraphViewState(component.loadCommitHistory, refs)
    val search = rememberSearchState(component.searchCommits, refs)
    val detail = rememberCommitDetailState(component.loadChangedFiles)
    val diff = rememberDiffViewerState()
    // 기록 경로를 가진 홀더는 **범위도 키다** — 새 범위가 생기면 홀더도 새로 만들어야 옛 이력에
    // 기록하지 않는다 (결정 G29).
    val staging = remember(repositoryPath, undoScope) { StagingState(undoScope.stagingActions, scope) }
    // 계속(continue)이 병합인지 리베이스인지는 **저장소를 열 때 읽은 상태**로 가른다. 홀더를 다시
    // 만들지 않고 최신 값을 읽게 하려고 State 를 그대로 읽는다 — remember 람다가 첫 context 를
    // 붙잡으면 저장소를 바꿔도 옛 상태로 계속을 시도한다.
    val conflict = remember(repositoryPath, undoScope) {
        ConflictState(
            actions = undoScope.conflictActions,
            repositoryState = { context.value.opened?.state ?: RepositoryState.NORMAL },
            scope = scope,
        )
    }
    // 리베이스 기준은 현재 브랜치의 upstream 이다 — upstream 이 없으면 비교 대상이 없어 대상도 없다.
    val rebase = remember(repositoryPath, undoScope) {
        RebasePlanState(
            actions = undoScope.rebaseActions,
            upstream = { context.value.currentBranch?.upstream },
            scope = scope,
        )
    }
    val sidebar = remember(repositoryPath, undoScope) {
        SidebarState(
            loadRefs = component.loadSidebarRefs,
            checkoutBranch = undoScope.checkoutBranch,
            renameBranch = component.renameBranch,
            deleteBranch = component.deleteBranch,
            scope = scope,
        )
    }
    LaunchedEffect(refs) {
        if (repositoryPath == null) return@LaunchedEffect
        try {
            sidebar.refresh()
            staging.refresh()
            conflict.refresh()
            graph.loadInitialPage()
        } catch (failure: UndineException) {
            errors.report(failure, logPath = null)
        }
    }

    return remember(graph, search, detail, diff, staging, conflict, rebase, sidebar, dragDrop) {
        RepositoryScreens(
            graph = graph,
            search = search,
            detail = detail,
            diff = diff,
            staging = staging,
            conflict = conflict,
            rebase = rebase,
            sidebar = sidebar,
            dragDrop = dragDrop,
        )
    }
}

/** 전역 실패 안내. 예외 원문이 아니라 종류와 로그 경로만 낸다. */
@Composable
private fun GlobalFailureBanner(failure: AppFailure, onDismiss: () -> Unit) {
    val tokens = UndineTokens.color
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.surface)
            .padding(UndineTokens.spacing.medium),
    ) {
        BasicText(
            text = "예상하지 못한 오류가 발생했습니다 (${failure.kind}).",
            style = UndineTokens.typography.body.copy(color = tokens.foregroundPrimary),
        )
        failure.logPath?.let { path ->
            BasicText(
                text = "자세한 내용: $path",
                style = UndineTokens.typography.caption.copy(color = tokens.foregroundSecondary),
            )
        }
        UndineToolbarButton(label = "닫기", onClick = onDismiss)
    }
}

fun main() {
    // 배선 누락은 **창을 띄우기 전에** 멈춘다 — 띄운 뒤 발견하면 사용자가 "그 화면이 없는 앱" 을 쓴다.
    verifyMenuReachesEveryDestination()
    application { UndineApplication() }
}

/** 창 하나와 그 안의 앱. `application` 스코프에서만 종료를 요청할 수 있어 여기서 조립한다. */
@Composable
private fun ApplicationScope.UndineApplication() {
    val errors = remember { AppErrorState() }
    val appDirectory = remember { appDirectory() }
    // 앱 디렉터리를 함께 넘긴다 — 설정 파일과 로그가 같은 곳에 있고, 경로 정책은 이 한 곳이 정한다.
    val component = remember {
        AppComponent(settingsFile = appDirectory.resolve("settings.json"), appDirectory = appDirectory)
    }

    LaunchedEffect(Unit) { installGlobalExceptionHandler(errors, appDirectory) }

    Window(onCloseRequest = { closeAndExit(component, ::exitApplication) }, title = windowTitle) {
        App(component = component, errors = errors, windowScope = this)
    }
}

/**
 * 열린 JGit 자원을 닫고 나서 종료한다.
 *
 * `runBlocking` 을 쓰는 이유는 **닫기가 끝난 뒤에** 종료해야 하기 때문이다 — 코루틴으로 띄우면
 * 프로세스가 먼저 내려가 pack 파일 핸들이 열린 채 남는다 (Windows 에서는 그 파일을 지울 수 없다).
 * 종료 경로는 진입점과 같은 축이라 여기서만 허용한다 (`kotlin-idioms` 규칙 10).
 *
 * 닫기 실패로 종료를 막지 않는다 — 사용자가 창을 닫으려는데 앱이 남아 있는 것이 더 나쁘다.
 * 대신 사유를 표준 오류로 남긴다.
 */
private fun closeAndExit(component: AppComponent, exit: () -> Unit) {
    try {
        runBlocking { component.closeRepository() }
    } catch (failure: UndineException) {
        System.err.println("종료 중 저장소를 닫지 못했습니다: ${failure.message}")
    }
    exit()
}
