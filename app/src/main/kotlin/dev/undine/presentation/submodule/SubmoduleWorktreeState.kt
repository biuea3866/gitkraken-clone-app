package dev.undine.presentation.submodule

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.worktree.UnsupportedWorktreeMetadata
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** 화면이 호출할 서브모듈 UseCase 묶음. Gateway 구현체는 presentation에 노출하지 않는다. */
data class SubmodulePanelActions(
    val load: LoadSubmodulesUseCase,
    val initialize: InitializeSubmoduleUseCase,
    val update: UpdateSubmoduleUseCase,
)

/** 화면이 호출할 worktree UseCase 묶음. */
data class WorktreePanelActions(
    val load: LoadWorktreesUseCase,
    val add: AddWorktreeUseCase,
    val remove: RemoveWorktreeUseCase,
)

enum class SubmoduleAction {
    INITIALIZE,
    OPEN,
    COMMIT_TO_PARENT,
    UPDATE_FROM_PARENT,
}

@Immutable
data class SubmoduleRowModel(
    val path: String,
    val initialized: Boolean,
    val locallyModified: Boolean,
    val divergedFromRecorded: Boolean,
    val actions: List<SubmoduleAction>,
)

/**
 * 서브모듈 패널의 상태 소유자.
 *
 * 세 상태 축은 enum으로 접지 않는다. 수정됨·어긋남이 동시에 참이면 각각의 안내와 동작이 함께
 * 남아야 사용자가 자기 변경을 잃지 않는다.
 */
@Stable
class SubmodulePanelState(
    private val actions: SubmodulePanelActions,
    private val scope: CoroutineScope,
    private val onOpenRequested: (String) -> Unit = {},
    private val onCommitToParentRequested: (String) -> Unit = {},
) {
    /**
     * 마지막으로 읽은 도메인 목록.
     *
     * 사이드바 하위 섹션이 같은 목록을 **다시 조회하지 않고** 여기서 받아 간다 — 패널과 사이드바가
     * 따로 조회하면 같은 화면에서 두 목록이 어긋난다.
     */
    var submodules: List<Submodule> by mutableStateOf(emptyList())
        private set

    var rows: List<SubmoduleRowModel> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /**
     * 변경 요청이 진행 중인가. 화면은 이 값으로 변경 버튼을 잠근다.
     *
     * `launch` **전에** 세운다 — 코루틴 안에서 세우면 같은 프레임의 두 번째 클릭이 첫 요청보다
     * 먼저 통과해 Gateway 호출과 Undo 기록이 두 번 남는다.
     */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** 조회가 성공했고 항목이 없을 때만 빈 상태를 보인다. 실패를 빈 목록으로 바꾸지 않는다. */
    val isEmpty: Boolean get() = !loading && failure == null && rows.isEmpty()

    fun refresh() {
        loading = true
        failure = null
        scope.launch {
            try {
                apply(actions.load.execute())
            } catch (thrown: UndineException) {
                failure = thrown
            } finally {
                loading = false
            }
        }
    }

    fun initialize(row: SubmoduleRowModel) = runOperation { actions.initialize.execute(row.path) }

    fun updateFromParent(row: SubmoduleRowModel) = runOperation { actions.update.execute(row.path) }

    fun requestOpen(row: SubmoduleRowModel) {
        if (SubmoduleAction.OPEN in row.actions) onOpenRequested(row.path)
    }

    /** 커밋 메시지 입력·실행은 기존 스테이징 화면에 맡기고, 여기서는 의도와 경로만 전달한다. */
    fun requestCommitToParent(row: SubmoduleRowModel) {
        if (SubmoduleAction.COMMIT_TO_PARENT in row.actions) onCommitToParentRequested(row.path)
    }

    private fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                operation()
                apply(actions.load.execute())
            } catch (thrown: UndineException) {
                failure = thrown
            } finally {
                busy = false
            }
        }
    }

    private fun apply(loaded: List<Submodule>) {
        submodules = loaded
        rows = loaded.map(::toRow)
    }
}

private fun toRow(submodule: Submodule): SubmoduleRowModel {
    val state = submodule.state
    val actions = when {
        !state.initialized -> listOf(SubmoduleAction.INITIALIZE)
        else -> buildList {
            add(SubmoduleAction.OPEN)
            if (state.locallyModified) add(SubmoduleAction.COMMIT_TO_PARENT)
            if (state.divergedFromRecorded) add(SubmoduleAction.UPDATE_FROM_PARENT)
        }
    }
    return SubmoduleRowModel(
        path = submodule.path,
        initialized = state.initialized,
        locallyModified = state.locallyModified,
        divergedFromRecorded = state.divergedFromRecorded,
        actions = actions,
    )
}

enum class WorktreeAction {
    OPEN,
    REMOVE,
    PRUNE,
}

@Immutable
data class WorktreeRowModel(
    val worktree: Worktree,
    val isCurrent: Boolean,
    val actions: List<WorktreeAction>,
)

/**
 * worktree 패널 상태. 제거 성공은 Gateway가 실제로 제거한 뒤에만 표시한다.
 *
 * 추가 입력(경로·브랜치)도 Composable 밖인 여기에 둔다 — 입력이 Composable 안에 있으면
 * 리컴포지션 규칙과 얽혀 검증할 수 없고, 화면 없이 제출 규칙을 고정할 수도 없다.
 *
 * @param ioDispatcher 경로 정규화 같은 파일시스템 I/O 를 넘길 디스패처. 기본값이 `Dispatchers.IO` 라
 *   호출부가 잊어도 UI 스레드에서 파일시스템을 읽지 않는다 (kotlin-idioms 11).
 */
@Stable
@Suppress("TooManyFunctions") // 목록 조회·열기·추가 입력·제거·prune 이 한 패널의 상태 전이다.
class WorktreePanelState(
    private val actions: WorktreePanelActions,
    private val currentWorktree: RepositoryPath,
    private val scope: CoroutineScope,
    private val onOpenRequested: (RepositoryPath) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var rows: List<WorktreeRowModel> by mutableStateOf(emptyList())
        private set

    var unsupported: List<UnsupportedWorktreeMetadata> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /** 더티 worktree는 삭제하지 않았다는 안내만 남긴다. */
    var dirtyRemovalPathCount: Int? by mutableStateOf(null)
        private set

    var lastRemovedName: String? by mutableStateOf(null)
        private set

    /** 추가 대화의 대상 디렉터리 입력. */
    var draftPath: String by mutableStateOf("")
        private set

    /** 추가 대화의 체크아웃 브랜치 입력. */
    var draftBranch: String by mutableStateOf("")
        private set

    /**
     * 변경 요청이 진행 중인가. 화면은 이 값으로 추가 입력과 제거·prune 버튼을 잠근다.
     *
     * `launch` **전에** 세운다 — 코루틴 안에서 세우면 같은 프레임의 두 번째 클릭이 첫 요청보다
     * 먼저 통과해 Gateway 호출과 Undo 기록이 두 번 남는다.
     */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** 사이드바 하위 섹션이 받아 가는 도메인 목록 — 패널이 읽은 그 목록이다. */
    val worktrees: List<Worktree> get() = rows.map { it.worktree }

    /** 두 입력이 모두 채워졌고 진행 중인 변경이 없을 때만 추가 요청을 낼 수 있다. */
    val canSubmitAdd: Boolean get() = !busy && draftPath.isNotBlank() && draftBranch.isNotBlank()

    val isEmpty: Boolean get() = !loading && failure == null && rows.isEmpty() && unsupported.isEmpty()

    fun refresh() {
        loading = true
        failure = null
        scope.launch { reload() }
    }

    fun requestOpen(row: WorktreeRowModel) {
        if (WorktreeAction.OPEN in row.actions) onOpenRequested(row.worktree.path)
    }

    fun updateDraftPath(text: String) {
        draftPath = text
    }

    fun updateDraftBranch(text: String) {
        draftBranch = text
    }

    /**
     * 입력한 경로·브랜치로 worktree 를 추가하고, 성공하면 목록을 다시 읽는다.
     *
     * 빈 입력은 요청하지 않는다 — 빈 경로로 Gateway 를 부르면 어디에 만들지 모르는 채로 실행된다.
     * 입력을 비우는 것은 **추가가 성공한 뒤**다. 실패했는데 비우면 사용자가 방금 친 값을 잃는다.
     */
    fun submitAdd() {
        if (!canSubmitAdd) return
        val path = RepositoryPath(draftPath.trim())
        val branch = RefName(draftBranch.trim())
        busy = true
        failure = null
        scope.launch {
            try {
                actions.add.execute(path, branch)
                draftPath = ""
                draftBranch = ""
                reload()
            } catch (thrown: UndineException) {
                failure = thrown
            } finally {
                busy = false
            }
        }
    }

    fun remove(row: WorktreeRowModel) {
        if (WorktreeAction.REMOVE !in row.actions) return
        removeExisting(row.worktree.name)
    }

    /** ORPHANED 정리는 별도 Gateway 계약을 만들지 않고 기존 remove(name)으로 한다. */
    fun prune(row: WorktreeRowModel) {
        if (WorktreeAction.PRUNE !in row.actions) return
        removeExisting(row.worktree.name)
    }

    private fun removeExisting(name: String) {
        if (busy) return
        busy = true
        dirtyRemovalPathCount = null
        failure = null
        lastRemovedName = null
        scope.launch {
            try {
                actions.remove.execute(name)
                lastRemovedName = name
                reload()
            } catch (dirty: UndineException.DirtyWorkingTree) {
                dirtyRemovalPathCount = dirty.paths.size
            } catch (thrown: UndineException) {
                failure = thrown
            } finally {
                busy = false
            }
        }
    }

    private suspend fun reload() {
        try {
            val listing = actions.load.execute()
            // 경로 정규화는 항목 수만큼 파일시스템을 두드린다. 결과를 다 모은 뒤에야 상태를 쓴다.
            val loaded = withContext(ioDispatcher) {
                val current = realPathOrNull(currentWorktree)
                listing.worktrees.map { worktree ->
                    val isCurrent = current != null && realPathOrNull(worktree.path) == current
                    WorktreeRowModel(worktree, isCurrent, actionsFor(worktree, isCurrent))
                }
            }
            unsupported = listing.unsupported
            rows = loaded
        } catch (thrown: UndineException) {
            failure = thrown
        } finally {
            loading = false
        }
    }
}

private fun actionsFor(worktree: Worktree, isCurrent: Boolean): List<WorktreeAction> = when (worktree.state) {
    WorktreeState.MAIN -> emptyList()
    WorktreeState.LINKED -> buildList {
        if (!isCurrent) add(WorktreeAction.OPEN)
        if (!isCurrent) add(WorktreeAction.REMOVE)
    }
    WorktreeState.ORPHANED -> listOf(WorktreeAction.PRUNE)
}

/**
 * 존재를 확인할 수 있는 경로만 정규화해 돌려준다. 읽을 수 없으면 `null` 이고, 그 항목은 같은
 * 문자열로 보이더라도 현재 worktree 라고 단정하지 않는다(E7).
 */
private fun realPathOrNull(path: RepositoryPath): Path? = try {
    Path.of(path.value).toRealPath()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
