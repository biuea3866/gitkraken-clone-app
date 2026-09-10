package dev.undine.presentation.sidebar

import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.sidebar.DeleteBranchUseCase
import dev.undine.application.sidebar.LoadSidebarRefsUseCase
import dev.undine.application.sidebar.RenameBranchUseCase
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.application.toolbar.FastForwardBranchUseCase
import dev.undine.application.toolbar.FetchRemoteUseCase
import dev.undine.application.toolbar.PullRemoteUseCase
import dev.undine.application.toolbar.PushRemoteUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Branch
import dev.undine.domain.CheckoutResult
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.worktree.Worktree
import dev.undine.presentation.contextmenu.GraphContextSelection
import dev.undine.presentation.contextmenu.GraphContextTarget
import dev.undine.presentation.contextmenu.GraphOperationKind
import dev.undine.presentation.contextmenu.graphMenuEntryOf
import dev.undine.presentation.toolbar.RemoteActions
import dev.undine.presentation.toolbar.RemoteToolbarState
import dev.undine.testsupport.PassThroughSessionBinding
import dev.undine.testsupport.baselineOf
import dev.undine.testsupport.commitId
import dev.undine.testsupport.recorderOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/** 커밋 해시 자리를 채우는 고정값 — 시간·난수에 의존하지 않는다 (testing 규칙 7). */
internal fun commitId(prefix: String): CommitId = CommitId.of(prefix.padEnd(40, '0'))

internal fun branchOf(
    name: String,
    isCurrent: Boolean = false,
    isRemote: Boolean = false,
    ahead: Int = 0,
    behind: Int = 0,
): Branch = Branch(
    name = RefName(name),
    target = commitId(name.filter { it.isDigit() }.ifEmpty { "a" }),
    isCurrent = isCurrent,
    isRemote = isRemote,
    upstream = if (isRemote) null else RefName("origin/$name"),
    ahead = ahead,
    behind = behind,
)

/**
 * 화면 배선과 **같은 방식**으로 만드는 병합 통로 — 가용성을 스텁으로 흉내내지 않고 그래프 조작의
 * 공용 판정([graphMenuEntryOf])에 묻는다 (결정 D17). 스텁으로 대신하면 진입점이 판정을 우회해도
 * 테스트가 초록불이 된다.
 */
internal fun mergeBinding(
    currentBranch: RefName? = RefName("main"),
    selectedCommit: CommitId? = commitId("f"),
    onRequest: (GraphOperation) -> Unit = {},
): SidebarMergeBinding = SidebarMergeBinding(
    entryOf = { branch ->
        graphMenuEntryOf(
            kind = GraphOperationKind.MERGE,
            target = GraphContextTarget.Branch(branch.name, branch.target, branch.isRemote),
            selection = GraphContextSelection(commit = selectedCommit, currentBranch = currentBranch),
        )
    },
    onRequest = onRequest,
)

/**
 * 화면 배선과 **같은 방식**으로 만드는 지목 조작 통로 — 가용성을 스텁으로 흉내내지 않고
 * 원격 작업 상태 홀더의 공용 판정([RemoteToolbarState.branchEntryOf])에 묻는다 (결정 D4).
 * 스텁으로 대신하면 진입점이 판정을 우회해도 테스트가 초록불이 된다.
 */
internal fun remoteBindingOn(
    toolbarState: RemoteToolbarState,
    onPull: (Branch) -> Unit = toolbarState::pullBranch,
    onPush: (Branch) -> Unit = toolbarState::pushBranch,
): SidebarRemoteBinding = SidebarRemoteBinding(
    entryOf = toolbarState::branchEntryOf,
    onPull = onPull,
    onPush = onPush,
)

/** 원격 목록·현재 브랜치만 정하면 되는 기본 홀더 — Gateway 는 호출되지 않는 대역이다. */
internal fun remoteToolbarStateFor(
    remotes: List<String> = listOf("origin"),
    currentBranch: Branch? = SAMPLE_MAIN,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
): RemoteToolbarState = RemoteToolbarState(
    scope = scope,
    actions = RemoteActions(
        fetchRemote = FetchRemoteUseCase(mockk()),
        pullRemote = PullRemoteUseCase(mockk()),
        pushRemote = PushRemoteUseCase(mockk(), recorderOf(UndoStack())),
        fastForwardBranch = FastForwardBranchUseCase(
            fetchRemote = FetchRemoteUseCase(mockk()),
            refGateway = mockk(),
            operationRecorder = recorderOf(UndoStack()),
            sessionBinding = PassThroughSessionBinding,
        ),
    ),
    remotes = remotes,
    branch = currentBranch,
)

internal fun tagOf(name: String): Tag = Tag(
    name = RefName(name),
    target = commitId("b"),
    isAnnotated = false,
    message = null,
    tagger = null,
)

internal fun stashOf(index: Int, message: String, includedUntracked: Boolean = false): StashEntry = StashEntry(
    index = index,
    message = message,
    target = commitId("c$index"),
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    includedUntracked = includedUntracked,
)

/** [sampleRefs] 가 담는 브랜치. 태그·메뉴를 집을 때 같은 값을 다시 적지 않도록 이름을 붙여 둔다. */
internal val SAMPLE_MAIN = branchOf("main", isCurrent = true, ahead = 2, behind = 1)
internal val SAMPLE_FEATURE = branchOf("feature/login")
internal val SAMPLE_REMOTE_MAIN = branchOf("origin/main", isRemote = true)

/** 세 그룹이 모두 채워진 기본 화면 데이터. */
internal fun sampleRefs(): SidebarRefs = SidebarRefs(
    branches = listOf(SAMPLE_MAIN, SAMPLE_FEATURE, SAMPLE_REMOTE_MAIN),
    tags = listOf(tagOf("v1.0.0")),
    stashes = listOf(stashOf(0, "작업 중", includedUntracked = true)),
)

/**
 * 실제 UseCase 를 Gateway mock 위에 얹은 상태 홀더.
 *
 * `Dispatchers.Unconfined` 스코프라 `launch` 가 호출 지점에서 그대로 실행돼
 * 코루틴 테스트 의존성 없이 결정적으로 검증된다 (wave 3 결정 A2 — 빌드 파일은 UND-12 소유).
 */
internal class SidebarStateHarness(
    val refGateway: RefGateway = mockk(relaxUnitFun = true),
    val worktreeOpsGateway: WorktreeOpsGateway = mockk(relaxUnitFun = true),
    submodules: () -> List<Submodule> = { emptyList() },
    worktrees: () -> List<Worktree> = { emptyList() },
    recorder: OperationRecorder = recorderOf(UndoStack()),
) {
    init {
        // 체크아웃은 이전 위치·기준 상태를 결과로 준다 (UND-73) — 반환값이 있어 relaxUnitFun 이 채우지 못한다.
        coEvery { refGateway.checkout(any(), any()) } returns CheckoutResult(
            previousRef = RefName("main"),
            baseline = baselineOf(commitId(1)),
        )
    }

    val state: SidebarState = SidebarState(
        loadRefs = LoadSidebarRefsUseCase(refGateway, worktreeOpsGateway),
        checkoutBranch = CheckoutBranchUseCase(refGateway, recorder),
        renameBranch = RenameBranchUseCase(refGateway),
        deleteBranch = DeleteBranchUseCase(refGateway),
        scope = CoroutineScope(Dispatchers.Unconfined),
        sections = SidebarSectionSource(submodules = submodules, worktrees = worktrees),
    )

    fun withRefs(refs: SidebarRefs = sampleRefs()): SidebarStateHarness {
        coEvery { refGateway.listBranches() } returns refs.branches
        coEvery { refGateway.listTags() } returns refs.tags
        coEvery { worktreeOpsGateway.stashList() } returns refs.stashes
        return this
    }

    /** 목록을 불러온 뒤의 상태를 돌려준다 — 대부분의 검증이 여기서 시작한다. */
    fun loaded(refs: SidebarRefs = sampleRefs()): SidebarState {
        withRefs(refs)
        state.refresh()
        return state
    }
}
