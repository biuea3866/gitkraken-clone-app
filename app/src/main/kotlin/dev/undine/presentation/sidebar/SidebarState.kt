package dev.undine.presentation.sidebar

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.sidebar.CheckoutBranchUseCase
import dev.undine.application.sidebar.DeleteBranchUseCase
import dev.undine.application.sidebar.LoadSidebarRefsUseCase
import dev.undine.application.sidebar.RenameBranchUseCase
import dev.undine.domain.Branch
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.worktree.Worktree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 서브모듈·worktree 하위 섹션이 그릴 목록의 공급자.
 *
 * 사이드바가 직접 조회하지 않고 **이미 그 목록을 읽은 패널 상태**에서 받아 온다 — 두 곳이 따로
 * 조회하면 같은 화면에서 두 목록이 어긋난다.
 */
class SidebarSectionSource(
    val submodules: () -> List<Submodule>,
    val worktrees: () -> List<Worktree>,
) {
    companion object {
        /**
         * 아직 패널을 배선하지 않은 호출부용 공급자.
         *
         * 이 값을 쓰면 두 하위 섹션은 머리행만 남고 항목이 0개다. `App.kt`·`di/` 는 UND-51 소관이라
         * 이 티켓이 고치지 않으므로, 그 배선이 실제 패널 상태를 넘겨줄 때까지의 자리다.
         */
        val NOT_WIRED: SidebarSectionSource =
            SidebarSectionSource(submodules = { emptyList() }, worktrees = { emptyList() })
    }
}

/**
 * 사이드바 화면 상태 홀더.
 *
 * **UseCase 만 알고 Gateway 는 모른다** (architecture-layers 규칙 3). 화면 연결(고른 참조를 다른
 * 화면에 전달하는 등)은 콜백으로 열어 두고 배선은 UND-26 이 한다.
 *
 * 실패를 빈 목록·성공으로 바꾸지 않는다 — 조회 실패는 [SidebarStatus.Failed], 조작 실패는
 * [actionFailure] 로 화면에 도달한다 (exception-handling 규칙 6·7). `UndineException` 만 잡으므로
 * 취소(`CancellationException`)는 그대로 전파된다 (규칙 5).
 *
 * @param scope 화면 수명에 묶인 스코프. 홀더가 스코프를 만들지 않아 화면이 사라지면 작업도 취소된다.
 * @param sections 서브모듈·worktree 하위 섹션 목록의 공급자. 실제 패널 상태를 넘기는 배선은
 *   `App.kt`·`di/` 를 소유한 UND-51 이 하므로, 그때까지의 기본값은
 *   [SidebarSectionSource.NOT_WIRED] 다 — 이름으로 "아직 안 채워졌다" 를 드러낸다.
 */
@Stable
@Suppress("TooManyFunctions") // 트리 조작·메뉴·삭제 2단계 확인·이름 변경이 한 화면의 상태 전이다.
class SidebarState(
    private val loadRefs: LoadSidebarRefsUseCase,
    private val checkoutBranch: CheckoutBranchUseCase,
    private val renameBranch: RenameBranchUseCase,
    private val deleteBranch: DeleteBranchUseCase,
    private val scope: CoroutineScope,
    private val sections: SidebarSectionSource = SidebarSectionSource.NOT_WIRED,
) {
    var status: SidebarStatus by mutableStateOf(SidebarStatus.Idle)
        private set

    /** 브랜치 이름 필터. 빈 문자열이면 전부 보인다. */
    var filter: String by mutableStateOf("")
        private set

    /**
     * 컨텍스트 메뉴가 열린 브랜치의 [refKey]. 한 번에 하나만 열린다.
     *
     * 짧은 이름이 아니라 종류를 담은 키인 이유는, 로컬 `origin/main` 과 원격 추적 `origin/main` 처럼
     * 이름이 겹칠 때 한 행을 열면 다른 행의 메뉴도 함께 열리기 때문이다.
     */
    var openMenu: String? by mutableStateOf(null)
        private set

    /** 확인을 기다리는 파괴적 동작. `null` 이면 대기 중인 것이 없다. */
    var confirmation: SidebarConfirmation? by mutableStateOf(null)
        private set

    /** 이름 변경 대화상자의 대상 브랜치. */
    var renameTarget: Branch? by mutableStateOf(null)
        private set

    /** 마지막 조작 실패. 다음 조작을 시작하거나 [dismiss] 하면 지워진다. */
    var actionFailure: UndineException? by mutableStateOf(null)
        private set

    /**
     * 삭제 요청이 Gateway 응답을 기다리는 중인지.
     *
     * 삭제는 reflog 로만 복구되므로 확인 입력이 연달아 들어와도 **한 번만** 나가야 한다.
     * 확인 버튼을 비활성화하는 근거이며 [confirmDelete] 의 재진입도 이 값으로 막는다.
     */
    var deleteInProgress: Boolean by mutableStateOf(false)
        private set

    /** 조회 세대. 늦게 끝난 과거 조회가 최신 목록을 덮어쓰지 않게 하는 기준이다 ([load]). */
    private var loadGeneration: Int = 0

    private var expandedGroups: Set<SidebarGroup> by mutableStateOf(SidebarGroup.entries.toSet())

    /** 필터·접힘을 적용한 트리 행. 의존 값이 바뀔 때만 다시 계산된다 (compose-ui 규칙 4). */
    private val flattenedNodes = derivedStateOf {
        when (val current = status) {
            is SidebarStatus.Ready -> buildSidebarNodes(
                current.refs,
                expandedGroups,
                filter,
                sections.submodules(),
                sections.worktrees(),
            )
            else -> emptyList()
        }
    }

    val nodes: List<SidebarNode> get() = flattenedNodes.value

    /** 참조 목록을 다시 읽는다. 저장소를 연 직후와 조작 성공 후에 호출한다. */
    fun refresh() {
        status = SidebarStatus.Loading
        scope.launch { load() }
    }

    fun updateFilter(text: String) {
        filter = text
    }

    fun toggleGroup(group: SidebarGroup) {
        expandedGroups = if (group in expandedGroups) expandedGroups - group else expandedGroups + group
    }

    fun toggleMenu(branch: Branch) {
        val key = branch.refKey()
        openMenu = if (openMenu == key) null else key
    }

    /** [branch] 행의 메뉴가 열려 있는지 — 화면이 이름이 아니라 종류까지 대조하게 한다. */
    fun isMenuOpen(branch: Branch): Boolean = openMenu == branch.refKey()

    /** 강제 없이 체크아웃한다 — 워킹트리가 더러우면 덮어쓰지 않고 실패가 [actionFailure] 로 온다. */
    fun checkout(branch: Branch) {
        openMenu = null
        runAction { checkoutBranch(branch.name) }
    }

    /**
     * 이름 변경은 **로컬 브랜치 전용**이다. 원격 추적 브랜치를 넘기면 아무 것도 하지 않는다 —
     * Gateway 는 짧은 이름을 `refs/heads/` 로 해석하므로, 원격 행의 이름 변경은 동명 로컬 브랜치를
     * 건드리게 된다. 화면도 원격 행에는 이 메뉴를 내지 않는다(이 검사는 두 번째 방어선이다).
     */
    fun startRename(branch: Branch) {
        if (branch.isRemote) return
        openMenu = null
        renameTarget = branch
    }

    /** 대화상자가 닫힌 뒤 호출되면 아무 것도 하지 않는다 — 대상이 없으면 이름 변경도 없다. */
    fun submitRename(newName: String) {
        val target = renameTarget ?: return
        renameTarget = null
        runAction { renameBranch(target.name, RefName(newName)) }
    }

    /**
     * 삭제 확인을 요청만 한다. 이 호출로는 어떤 삭제도 실행되지 않는다.
     *
     * 삭제도 **로컬 브랜치 전용**이다 — [startRename] 과 같은 이유로, 원격 행의 삭제는 Gateway 에서
     * `refs/heads/<같은 이름>` 을 지우는 요청이 되어 **사용자가 보지 않은 로컬 브랜치를 지운다**.
     * 미병합이면 강제 삭제 확인까지 이어져 되돌릴 수 없다.
     */
    fun requestDelete(branch: Branch) {
        if (branch.isRemote) return
        openMenu = null
        confirmation = SidebarConfirmation.DeleteBranch(branch)
    }

    /**
     * 확인받은 단계만 실행한다. 대기 중인 확인이 없으면 아무 것도 하지 않는다.
     *
     * 이미 나간 삭제가 응답을 기다리는 중이면 재진입하지 않는다 — 대상·`force` 는 Gateway 호출을
     * 시작하기 전에 [deleteInProgress] 와 함께 한 번에 소비되므로, 연속 확인 입력이 같은 단계를
     * 두 번 삭제하지 않는다.
     */
    fun confirmDelete() {
        if (deleteInProgress) return
        when (val pending = confirmation) {
            null -> Unit
            is SidebarConfirmation.DeleteBranch -> delete(pending.branch, force = false)
            is SidebarConfirmation.ForceDeleteUnmerged -> delete(pending.branch, force = true)
        }
    }

    /** 열린 메뉴·대화상자·실패 안내를 닫는다. 파괴적 동작은 실행하지 않는다. */
    fun dismiss() {
        confirmation = null
        renameTarget = null
        openMenu = null
        actionFailure = null
    }

    private fun delete(branch: Branch, force: Boolean) {
        actionFailure = null
        deleteInProgress = true
        // 이 시도가 시작될 때 화면에 떠 있던 확인. 결과가 돌아왔을 때도 같은 확인이 떠 있을 때만 반영한다.
        val startedFrom = confirmation
        scope.launch {
            try {
                val result = try {
                    deleteBranch(branch.name, force)
                } catch (failure: UndineException) {
                    if (isStale(startedFrom)) return@launch
                    actionFailure = failure
                    confirmation = null
                    return@launch
                }
                if (isStale(startedFrom)) return@launch
                applyDeleteResult(branch, result)
            } finally {
                deleteInProgress = false
            }
        }
    }

    /**
     * 시도를 시작한 뒤 사용자가 대화상자를 닫았거나 다른 확인으로 넘어갔는지 본다.
     *
     * 늦게 도착한 결과를 그대로 쓰면 **사용자가 취소한 대화상자가 다시 열리거나**(REFUSED_UNMERGED 가
     * 강제 삭제 확인을 세운다), 지금 보고 있는 다른 브랜치의 확인이 지워진다. 어느 쪽도 파괴적 동작의
     * 대상을 흐리므로, 화면이 그 사이 움직였으면 결과를 버린다.
     */
    private fun isStale(startedFrom: SidebarConfirmation?): Boolean = confirmation !== startedFrom

    /**
     * 비강제 삭제가 거부된 것은 실패가 아니라 **한 단계 더 확인받아야 한다는 신호**다.
     * 강제 삭제까지 거부되는 경우는 `RefGateway` 계약상 없지만, 그때도 확인 단계에 머물러
     * 삭제되지 않은 것을 성공처럼 보이게 하지 않는다.
     */
    private suspend fun applyDeleteResult(branch: Branch, result: DeleteBranchResult) {
        when (result) {
            DeleteBranchResult.DELETED -> {
                confirmation = null
                load()
            }

            DeleteBranchResult.REFUSED_UNMERGED ->
                confirmation = SidebarConfirmation.ForceDeleteUnmerged(branch)
        }
    }

    /** 조작 후 목록을 다시 읽는다. 조작은 성공했는데 갱신이 실패하면 그 실패는 [status] 에 남는다. */
    private fun runAction(action: suspend () -> Unit) {
        actionFailure = null
        scope.launch {
            try {
                action()
            } catch (failure: UndineException) {
                actionFailure = failure
                return@launch
            }
            load()
        }
    }

    /**
     * 참조 목록을 읽어 상태에 반영한다.
     *
     * 조회는 여러 곳(새로고침·체크아웃·이름 변경·삭제 후)에서 독립적으로 시작하므로 **끝나는 순서가
     * 시작 순서와 다를 수 있다.** 먼저 시작한 조회가 늦게 끝나면 최신 목록을 과거 스냅샷으로 덮어쓴다 —
     * 방금 지운 브랜치가 목록에 되살아나는 식이다. 그래서 세대 번호를 찍어 **가장 마지막에 시작한
     * 조회만** 상태를 쓴다.
     */
    private suspend fun load() {
        val startedGeneration = ++loadGeneration
        val result = try {
            SidebarStatus.Ready(loadRefs())
        } catch (failure: UndineException) {
            SidebarStatus.Failed(failure)
        }
        if (startedGeneration != loadGeneration) return
        status = result
    }
}
