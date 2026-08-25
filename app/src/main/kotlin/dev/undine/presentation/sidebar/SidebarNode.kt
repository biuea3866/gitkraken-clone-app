package dev.undine.presentation.sidebar

import androidx.compose.runtime.Immutable
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.Branch
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.worktree.Worktree

private const val GROUP_KEY_PREFIX = "group:"
private const val LOCAL_BRANCH_KEY_PREFIX = "local:"
private const val REMOTE_BRANCH_KEY_PREFIX = "remote:"
private const val TAG_KEY_PREFIX = "tag:"
private const val STASH_KEY_PREFIX = "stash:"
private const val SUBMODULE_KEY_PREFIX = "submodule:"
private const val WORKTREE_KEY_PREFIX = "worktree:"

/**
 * 평탄화된 트리의 한 행.
 *
 * [key] 는 `LazyColumn` 이 항목 정체성을 유지하는 값이다 (compose-ui 규칙 3) — 필터나 접힘이
 * 바뀌어도 같은 참조는 같은 키를 갖는다. 브랜치·태그는 ref 이름을, 스태시는 커밋 대상을 쓰고
 * 종류별 접두사로 동명 참조끼리 충돌하지 않게 한다.
 */
@Immutable
sealed interface SidebarNode {

    val key: String

    /** 그룹 머리행. [itemCount] 는 접혀 있어도 필터 적용 후 항목 수를 그대로 보여준다. */
    data class GroupHeader(
        val group: SidebarGroup,
        val expanded: Boolean,
        val itemCount: Int,
    ) : SidebarNode {
        override val key: String get() = GROUP_KEY_PREFIX + group.name
    }

    data class BranchRow(val branch: Branch) : SidebarNode {
        override val key: String
            get() {
                val prefix = if (branch.isRemote) REMOTE_BRANCH_KEY_PREFIX else LOCAL_BRANCH_KEY_PREFIX
                return prefix + branch.name.value
            }
    }

    data class TagRow(val tag: Tag) : SidebarNode {
        override val key: String get() = TAG_KEY_PREFIX + tag.name.value
    }

    data class StashRow(val entry: StashEntry) : SidebarNode {
        override val key: String get() = STASH_KEY_PREFIX + entry.target.value
    }

    /** 서브모듈 패널로 연결할 사이드바 행. 상태 상세와 동작은 UND-45 전용 패널이 소유한다. */
    data class SubmoduleRow(val submodule: Submodule) : SidebarNode {
        override val key: String get() = SUBMODULE_KEY_PREFIX + submodule.path
    }

    /** worktree 패널로 연결할 사이드바 행. 경로가 같은 항목을 안정적으로 식별한다. */
    data class WorktreeRow(val worktree: Worktree) : SidebarNode {
        override val key: String get() = WORKTREE_KEY_PREFIX + worktree.name
    }
}

/** 지금 목록에 보이는 브랜치 행 수. 0 이면 화면이 빈 상태 안내를 대신 띄운다. */
val List<SidebarNode>.visibleBranchCount: Int
    get() = filterIsInstance<SidebarNode.GroupHeader>()
        .filter { it.group == SidebarGroup.LOCAL_BRANCHES || it.group == SidebarGroup.REMOTE_BRANCHES }
        .sumOf { it.itemCount }

/**
 * 참조 목록을 화면 순서대로 평탄화한다.
 *
 * [filter] 는 **브랜치에만** 적용된다 — 필터 입력의 목적이 수백 개 브랜치를 좁히는 것이고,
 * 태그·스태시까지 함께 사라지면 필터를 지우기 전까지 그 그룹이 비어 보인다.
 * 접힌 그룹은 머리행만 남기고 항목을 내보내지 않아 `LazyColumn` 이 그리지 않는다.
 */
fun buildSidebarNodes(
    refs: SidebarRefs,
    expandedGroups: Set<SidebarGroup>,
    filter: String,
    submodules: List<Submodule> = emptyList(),
    worktrees: List<Worktree> = emptyList(),
): List<SidebarNode> {
    val matching = refs.branches.filter { it.name.value.contains(filter, ignoreCase = true) }
    val nodes = mutableListOf<SidebarNode>()

    nodes.appendGroup(SidebarGroup.LOCAL_BRANCHES, expandedGroups, matching.filterNot { it.isRemote }) {
        SidebarNode.BranchRow(it)
    }
    nodes.appendGroup(SidebarGroup.REMOTE_BRANCHES, expandedGroups, matching.filter { it.isRemote }) {
        SidebarNode.BranchRow(it)
    }
    nodes.appendGroup(SidebarGroup.TAGS, expandedGroups, refs.tags) { SidebarNode.TagRow(it) }
    nodes.appendGroup(SidebarGroup.STASHES, expandedGroups, refs.stashes) { SidebarNode.StashRow(it) }
    nodes.appendGroup(SidebarGroup.SUBMODULES, expandedGroups, submodules) { SidebarNode.SubmoduleRow(it) }
    nodes.appendGroup(SidebarGroup.WORKTREES, expandedGroups, worktrees) { SidebarNode.WorktreeRow(it) }

    return nodes
}

private fun <T> MutableList<SidebarNode>.appendGroup(
    group: SidebarGroup,
    expandedGroups: Set<SidebarGroup>,
    items: List<T>,
    toNode: (T) -> SidebarNode,
) {
    val expanded = group in expandedGroups
    add(SidebarNode.GroupHeader(group = group, expanded = expanded, itemCount = items.size))
    if (expanded) items.mapTo(this, toNode)
}
