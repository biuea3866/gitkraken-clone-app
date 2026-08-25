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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    var rows: List<SubmoduleRowModel> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    var failure: UndineException? by mutableStateOf(null)
        private set

    /** 조회가 성공했고 항목이 없을 때만 빈 상태를 보인다. 실패를 빈 목록으로 바꾸지 않는다. */
    val isEmpty: Boolean get() = !loading && failure == null && rows.isEmpty()

    fun refresh() {
        loading = true
        failure = null
        scope.launch {
            try {
                rows = actions.load.execute().map(::toRow)
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
        failure = null
        scope.launch {
            try {
                operation()
                rows = actions.load.execute().map(::toRow)
            } catch (thrown: UndineException) {
                failure = thrown
            }
        }
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

/** worktree 패널 상태. 제거 성공은 Gateway가 실제로 제거한 뒤에만 표시한다. */
@Stable
class WorktreePanelState(
    private val actions: WorktreePanelActions,
    private val currentWorktree: RepositoryPath,
    private val scope: CoroutineScope,
    private val onOpenRequested: (RepositoryPath) -> Unit = {},
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

    val isEmpty: Boolean get() = !loading && failure == null && rows.isEmpty() && unsupported.isEmpty()

    fun refresh() {
        loading = true
        failure = null
        scope.launch { reload() }
    }

    fun requestOpen(row: WorktreeRowModel) {
        if (WorktreeAction.OPEN in row.actions) onOpenRequested(row.worktree.path)
    }

    /** 새 worktree 추가가 끝나면 목록을 다시 읽어 성공한 항목만 화면에 보이게 한다. */
    fun add(path: RepositoryPath, branch: RefName) {
        failure = null
        scope.launch {
            try {
                actions.add.execute(path, branch)
                reload()
            } catch (thrown: UndineException) {
                failure = thrown
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
            }
        }
    }

    private suspend fun reload() {
        try {
            val listing = actions.load.execute()
            unsupported = listing.unsupported
            rows = listing.worktrees.map { worktree ->
                val current = normalizedPathEquals(worktree.path, currentWorktree)
                WorktreeRowModel(worktree, current, actionsFor(worktree, current))
            }
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
 * 존재를 확인할 수 있는 경로만 현재 worktree로 표시한다. 읽을 수 없는 경로는 같은 문자열로
 * 보이더라도 활성이라고 단정하지 않는다(E7).
 */
private fun normalizedPathEquals(left: RepositoryPath, right: RepositoryPath): Boolean = try {
    Path.of(left.value).toRealPath() == Path.of(right.value).toRealPath()
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
