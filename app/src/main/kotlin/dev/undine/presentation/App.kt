package dev.undine.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.undine.BuildInfo
import dev.undine.di.AppComponent
import dev.undine.domain.Branch
import dev.undine.domain.Commit
import dev.undine.domain.DiffResult
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.Tag
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
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
import dev.undine.presentation.graph.rememberGraphViewState
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.systemStrings
import dev.undine.presentation.palette.CommandPalette
import dev.undine.presentation.palette.CommandRegistry
import dev.undine.presentation.palette.commandShortcuts
import dev.undine.presentation.palette.rememberCommandCenter
import dev.undine.presentation.rebase.RebasePlanEditor
import dev.undine.presentation.rebase.RebasePlanState
import dev.undine.presentation.search.SearchPanel
import dev.undine.presentation.search.rememberSearchState
import dev.undine.presentation.shell.AppShell
import dev.undine.presentation.shell.AppShellState
import dev.undine.presentation.shell.AppShellSlots
import dev.undine.presentation.shell.rememberAppShellState
import dev.undine.presentation.staging.StagingPanel
import dev.undine.presentation.staging.StagingState
import dev.undine.presentation.sidebar.SidebarState
import dev.undine.presentation.sidebar.SidebarTree
import dev.undine.presentation.toolbar.RemoteToolbar
import dev.undine.presentation.toolbar.rememberRemoteToolbarState
import dev.undine.presentation.welcome.WelcomeCloneEvents
import dev.undine.presentation.welcome.WelcomeEvents
import dev.undine.presentation.welcome.WelcomeScreen
import dev.undine.presentation.welcome.WelcomeState
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import kotlinx.coroutines.runBlocking

/** 창 제목에 버전을 붙인다 — 사용자가 어느 빌드를 쓰는지 물어볼 때 답할 데가 필요하다. */
private val windowTitle: String = "Undine ${BuildInfo.VERSION}"

/**
 * 저장소를 연 뒤 알게 되는 것들의 스냅샷. 함께 읽고 함께 바뀌므로 한 값으로 묶는다 —
 * 셋을 따로 넘기면 어느 하나만 갱신된 중간 상태가 화면에 도달할 수 있다.
 */
@Immutable
private data class RepositoryContext(
    val opened: OpenedRepository? = null,
    val branches: List<Branch> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val remotes: List<String> = emptyList(),
)

/**
 * 고른 커밋을 들여다보는 데 필요한 값 묶음. 커밋·파일 선택이 바뀌면 함께 바뀌므로 하나로 묶는다.
 */
@Immutable
private data class CommitInspection(
    val commit: Commit?,
    val detailState: CommitDetailState,
    val diffState: DiffViewerState,
    val fileDiff: DiffResult?,
    val filePath: String?,
)

/** 설정·로그를 두는 사용자 디렉터리. 앱 전용 하위 디렉터리 하나만 쓴다. */
private fun appDirectory(): Path =
    Paths.get(System.getProperty("user.home"), ".undine")

/**
 * 앱 전체 배선.
 *
 * 저장소가 선택되지 않았으면 환영 화면, 선택됐으면 셸 3분할을 그린다. 슬롯에 꽂히는 컴포넌트는
 * 셸이 알지 못하고(`AppShellSlots`), 선택 흐름(커밋 → 상세 → diff)은 여기서 단방향으로 잇는다.
 *
 * **화면은 UseCase 만 받는다** — Gateway 는 [AppComponent] 안에 갇혀 있다.
 */
@Composable
fun App(
    component: AppComponent,
    errors: AppErrorState,
    modifier: Modifier = Modifier,
) {
    // 기본은 다크다. Git 클라이언트는 이력·diff 를 오래 들여다보는 화면이고, 설정 화면(UND-40)이
    // 붙기 전까지 사용자가 바꿀 수단이 없으므로 기본값이 곧 유일한 선택이다.
    UndineTheme(themeMode = ThemeMode.DARK) {
        CompositionLocalProvider(LocalStrings provides systemStrings()) {
            Box(modifier = modifier.fillMaxSize().background(UndineTokens.color.background)) {
                AppContent(component = component, errors = errors)
                errors.failure?.let { failure ->
                    GlobalFailureBanner(failure = failure, onDismiss = errors::dismiss)
                }
            }
        }
    }
}

@Composable
private fun AppContent(component: AppComponent, errors: AppErrorState) {
    val scope = rememberCoroutineScope()
    val shellState = rememberAppShellState()
    val selection = shellState.selection

    // 저장소를 열면 채워지는 컨텍스트. 저장소를 바꾸면 전부 비운다 — 이전 저장소의 참조가 남으면
    // 그래프·사이드바가 없는 커밋을 가리킨다.
    var context by remember { mutableStateOf(RepositoryContext()) }

    val welcomeState = remember {
        WelcomeState(
            actions = component.welcomeActions,
            scope = scope,
            onRepositoryOpened = { path -> shellState.selectRepository(path) },
        )
    }

    // 저장소가 바뀌면 참조를 다시 읽는다. 실패는 전역 안내로 올린다 — 조용히 빈 목록으로 만들지 않는다.
    LaunchedEffect(selection.repository) {
        val path = selection.repository
        if (path == null) {
            context = RepositoryContext()
            return@LaunchedEffect
        }
        try {
            // 한 번에 세운다 — 부분 갱신이 화면에 보이면 참조 없는 그래프가 잠시 그려진다.
            context = RepositoryContext(
                opened = component.openRepository(path),
                branches = component.listBranches(),
                tags = component.listTags(),
                remotes = component.listRemotes(),
            )
        } catch (failure: UndineException) {
            errors.report(failure, logPath = null)
            shellState.selectRepository(null)
        }
    }

    when (val path = selection.repository) {
        null -> WelcomeArea(state = welcomeState)
        else -> RepositoryArea(
            component = component,
            shellState = shellState,
            repositoryPath = path,
            context = context,
        )
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
 * 저장소를 연 뒤의 메인 화면.
 *
 * 선택 흐름은 한 방향이다: 그래프·검색이 커밋을 고르면 셸 상태가 바뀌고, 상세 패널이 그 커밋의
 * 파일을 내고, 파일을 고르면 diff 가 그 파일을 읽는다. 아래로만 흐르므로 어느 화면도 다른 화면의
 * 상태를 직접 만지지 않는다.
 */
@Composable
@Suppress("LongMethod") // 배선은 조립 순서를 한눈에 보여야 한다 — 쪼개면 흐름이 흩어진다.
private fun RepositoryArea(
    component: AppComponent,
    shellState: AppShellState,
    repositoryPath: RepositoryPath,
    context: RepositoryContext,
) {
    val scope = rememberCoroutineScope()
    val selection = shellState.selection
    // 팔레트 열기 요청. 커맨드 action 은 non-composable 이라 여기서 신호만 세우고 열기는 효과가 한다.
    var paletteRequested by remember { mutableStateOf(false) }

    // 그래프·검색이 훑는 참조. 결정문 A4 대로 현재 브랜치 하나로 시작한다.
    val refs = remember(context) { listOfNotNull(context.opened?.currentBranch) }
    val refIndex = remember(context) {
        CommitRefIndex.of(context.branches, context.tags, context.opened?.currentBranch)
    }

    val graphState = rememberGraphViewState(component.loadCommitHistory, refs)
    val searchState = rememberSearchState(component.searchCommits, refs)
    val detailState = rememberCommitDetailState(component.loadChangedFiles)
    val diffState = rememberDiffViewerState()
    val stagingState = remember(repositoryPath) {
        StagingState(actions = component.stagingActions, scope = scope)
    }
    // 계속(continue)이 병합인지 리베이스인지는 **저장소를 열 때 읽은 상태**로 가른다. 홀더를 다시
    // 만들지 않고 최신 값을 읽게 하려고 rememberUpdatedState 를 거친다 — remember 람다가 첫
    // context 를 붙잡으면 저장소를 바꿔도 옛 상태로 계속을 시도한다.
    val latestContext = rememberUpdatedState(context)
    val conflictState = remember(repositoryPath) {
        ConflictState(
            actions = component.conflictActions,
            repositoryState = { latestContext.value.opened?.state ?: RepositoryState.NORMAL },
            scope = scope,
        )
    }
    // 리베이스 기준은 현재 브랜치의 upstream 이다 — upstream 이 없으면 비교 대상이 없어 대상도 없다.
    val rebaseState = remember(repositoryPath) {
        RebasePlanState(
            actions = component.rebaseActions,
            upstream = { latestContext.value.branches.firstOrNull { it.isCurrent }?.upstream },
            scope = scope,
        )
    }
    val sidebarState = remember(repositoryPath) {
        SidebarState(
            loadRefs = component.loadSidebarRefs,
            checkoutBranch = component.checkoutBranch,
            renameBranch = component.renameBranch,
            deleteBranch = component.deleteBranch,
            scope = scope,
        )
    }
    val toolbarState = rememberRemoteToolbarState(
        fetchRemote = component.fetchRemote,
        pullRemote = component.pullRemote,
        pushRemote = component.pushRemote,
        remotes = context.remotes,
        branch = context.branches.firstOrNull { it.isCurrent },
    )

    // 등록은 한 번만 한다 — 같은 단축키를 두 번 등록하면 레지스트리가 예외를 던진다.
    val registry = remember(repositoryPath) {
        CommandRegistry().also { target ->
            registerAppCommands(
                registry = target,
                handlers = AppCommandHandlers(
                    onOpenPalette = { paletteRequested = true },
                    onCloseRepository = { shellState.selectRepository(null) },
                    onRefreshRefs = { sidebarState.refresh() },
                    onToggleDiffView = diffState::toggleViewMode,
                    onOpenRebasePlan = rebaseState::load,
                ),
            )
        }
    }
    val commandCenter = rememberCommandCenter(registry)
    LaunchedEffect(paletteRequested) {
        if (paletteRequested) {
            commandCenter.paletteState.open()
            paletteRequested = false
        }
    }

    LaunchedEffect(refs) {
        sidebarState.refresh()
        stagingState.refresh()
        conflictState.refresh()
        graphState.loadInitialPage()
    }

    // 고른 커밋의 변경 파일. 커밋이 바뀌면 다시 읽는다.
    LaunchedEffect(selection.commit) {
        selection.commit?.let { commit -> detailState.load(commit, parentIndex = 0) }
    }

    // 고른 파일의 diff. 파일이나 커밋이 바뀌면 다시 읽는다.
    var fileDiff by remember { mutableStateOf<DiffResult?>(null) }
    LaunchedEffect(selection.commit, selection.filePath) {
        val commit = selection.commit
        val filePath = selection.filePath
        fileDiff = if (commit == null || filePath == null) {
            null
        } else {
            runCatching { component.loadFileDiff.execute(commit, filePath) }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusTarget()
            .commandShortcuts(commandCenter.shortcutHandler),
    ) {
        AppShell(
            state = shellState,
            slots = AppShellSlots(
                toolbar = { RemoteToolbar(state = toolbarState) },
                sidebar = {
                    SidebarTree(
                        state = sidebarState,
                        opened = context.opened,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                center = {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SearchPanel(
                            state = searchState,
                            modifier = Modifier.fillMaxWidth(),
                            onCommitSelected = { commit -> shellState.selectCommit(commit.id) },
                        )
                        CommitGraphView(
                            state = graphState,
                            now = Clock.systemDefaultZone().instant(),
                            modifier = Modifier.fillMaxSize(),
                            refIndex = refIndex,
                            onCommitSelected = { commit -> shellState.selectCommit(commit.id) },
                        )
                    }
                },
                bottom = {
                    BottomArea(
                        inspection = CommitInspection(
                            commit = graphState.selectedCommit,
                            detailState = detailState,
                            diffState = diffState,
                            fileDiff = fileDiff,
                            filePath = selection.filePath,
                        ),
                        stagingState = stagingState,
                        conflictState = conflictState,
                        rebaseState = rebaseState,
                        onSelectFile = { filePath -> shellState.selectFile(filePath) },
                    )
                },
            ),
        )
        CommandPalette(state = commandCenter.paletteState)
    }
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

fun main() = application {
    val errors = remember { AppErrorState() }
    val appDirectory = remember { appDirectory() }
    val component = remember { AppComponent(appDirectory.resolve("settings.json")) }

    LaunchedEffect(Unit) { installGlobalExceptionHandler(errors, appDirectory) }

    Window(onCloseRequest = { closeAndExit(component, ::exitApplication) }, title = windowTitle) {
        App(component = component, errors = errors)
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
