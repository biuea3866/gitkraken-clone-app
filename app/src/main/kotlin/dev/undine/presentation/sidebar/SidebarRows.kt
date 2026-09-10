package dev.undine.presentation.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.Branch
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry
import dev.undine.domain.Tag
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.contextmenu.GraphMenuEntry
import dev.undine.presentation.contextmenu.contextMenuTrigger
import dev.undine.presentation.toolbar.BranchRemoteOperation
import dev.undine.presentation.toolbar.BranchRemoteRefusal
import dev.undine.presentation.i18n.contextMenu
import dev.undine.presentation.i18n.sidebar
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.submoduleWorktree

private const val EXPANDED_GLYPH = "▾"
private const val COLLAPSED_GLYPH = "▸"
private const val CURRENT_GLYPH = "●"
private const val MENU_GLYPH = "⋯"
private const val STASH_LABEL_PREFIX = "stash@{"
private const val STASH_LABEL_SUFFIX = "}"
private const val SINGLE_LINE = 1

/** 접을 수 있는 그룹 머리행. 항목 수는 접혀 있어도 그대로 보여준다. */
@Composable
internal fun SidebarGroupHeaderRow(header: SidebarNode.GroupHeader, onToggle: () -> Unit) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    UndineListRow(onClick = onToggle, modifier = Modifier.testTag(SidebarTags.group(header.group))) {
        BasicText(
            text = if (header.expanded) EXPANDED_GLYPH else COLLAPSED_GLYPH,
            style = typography.caption.copy(color = colors.foregroundSecondary),
        )
        BasicText(
            text = groupLabel(header.group),
            modifier = Modifier.weight(1f),
            style = typography.body.copy(color = colors.foregroundSecondary),
            maxLines = SINGLE_LINE,
        )
        BasicText(
            text = header.itemCount.toString(),
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
    }
}

@Composable
private fun groupLabel(group: SidebarGroup): String {
    val sidebarStrings = strings.sidebar
    return when (group) {
        SidebarGroup.LOCAL_BRANCHES -> sidebarStrings.localBranches
        SidebarGroup.REMOTE_BRANCHES -> sidebarStrings.remoteBranches
        SidebarGroup.TAGS -> sidebarStrings.tags
        SidebarGroup.STASHES -> sidebarStrings.stashes
        SidebarGroup.SUBMODULES -> strings.submoduleWorktree.submodulesTitle
        SidebarGroup.WORKTREES -> strings.submoduleWorktree.worktreesTitle
    }
}

/**
 * 브랜치 한 행과 그 행에서 열리는 컨텍스트 메뉴.
 *
 * 배지 값은 [branch] 에 이미 실려 있어 **행 렌더링 중 추가 조회를 하지 않는다.**
 *
 * **우클릭과 `⋯` 버튼이 같은 [SidebarBranchMenu] 를 연다** — 두 진입점이 다른 목록을 보이면
 * 사용자는 어느 쪽이 전부인지 알 수 없다 (결정 D2). 우클릭은 여는 것이지 접는 것이 아니라서
 * 토글이 아니라 열기로 둔다.
 */
@Composable
internal fun SidebarBranchItem(
    branch: Branch,
    state: SidebarState,
    merge: SidebarMergeBinding,
    remote: SidebarRemoteBinding,
) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val sidebarStrings = strings.sidebar

    Column(modifier = Modifier.fillMaxWidth()) {
        UndineListRow(
            onClick = { state.toggleMenu(branch) },
            modifier = Modifier
                .contextMenuTrigger { state.showMenu(branch) }
                .testTag(SidebarTags.branchRow(branch)),
            selected = branch.isCurrent,
        ) {
            if (branch.isCurrent) {
                val currentLabel = sidebarStrings.currentBranch
                BasicText(
                    text = CURRENT_GLYPH,
                    // 행이 클릭 가능해 하위 시맨틱을 합치므로, 표식은 자기 경계를 세워 따로 남는다.
                    modifier = Modifier
                        .semantics(mergeDescendants = true) { contentDescription = currentLabel }
                        .testTag(SidebarTags.currentMarker(branch.name)),
                    style = typography.caption.copy(color = colors.accent),
                )
            }
            BasicText(
                text = branch.name.value,
                modifier = Modifier.weight(1f),
                style = typography.body.copy(color = colors.foregroundPrimary),
                maxLines = SINGLE_LINE,
                overflow = TextOverflow.Ellipsis,
            )
            SidebarBadge.of(branch)?.let { badge -> AheadBehindBadge(name = branch.name, badge = badge) }
            UndineToolbarButton(
                label = MENU_GLYPH,
                onClick = { state.toggleMenu(branch) },
                modifier = Modifier
                    .semantics { contentDescription = sidebarStrings.menuOpen }
                    .testTag(SidebarTags.menuButton(branch)),
            )
        }
        if (state.isMenuOpen(branch)) {
            SidebarBranchMenu(branch = branch, state = state, merge = merge, remote = remote)
        }
    }
}

/** `2↑ 1↓` 배지. 0 인 쪽은 숫자를 내지 않는다. */
@Composable
private fun AheadBehindBadge(name: RefName, badge: SidebarBadge) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val sidebarStrings = strings.sidebar

    Row(
        // 행이 하위 시맨틱을 합치므로 배지도 자기 경계를 세운다 — 배지 문구가 행 텍스트에 섞이지 않는다.
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .testTag(SidebarTags.badge(name)),
        horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        if (badge.ahead > 0) {
            BasicText(
                text = sidebarStrings.ahead(badge.ahead),
                style = typography.caption.copy(color = colors.addition),
            )
        }
        if (badge.behind > 0) {
            BasicText(
                text = sidebarStrings.behind(badge.behind),
                style = typography.caption.copy(color = colors.warning),
            )
        }
    }
}

/**
 * 브랜치 컨텍스트 메뉴. 행 아래에 펼쳐 두므로 마우스 없이도 포커스를 옮겨 Enter 로 실행할 수 있다
 * (compose-ui 규칙 8).
 *
 * 삭제는 **여기서 실행되지 않는다** — 확인 절차를 여는 요청만 보낸다.
 */
@Composable
private fun SidebarBranchMenu(
    branch: Branch,
    state: SidebarState,
    merge: SidebarMergeBinding,
    remote: SidebarRemoteBinding,
) {
    val spacing = UndineTokens.spacing
    val sidebarStrings = strings.sidebar

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.large, end = spacing.small, bottom = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        UndineToolbarButton(
            label = sidebarStrings.menuCheckout,
            onClick = { state.checkout(branch) },
            modifier = Modifier.testTag(SidebarTags.MENU_CHECKOUT),
        )
        // 이름 변경·삭제는 `refs/heads/` 를 대상으로 하는 로컬 전용 조작이다. 원격 행에 내면
        // 사용자는 원격을 지운다고 믿지만 실제로는 동명 로컬 브랜치가 지워진다 — 아예 노출하지 않는다.
        if (!branch.isRemote) {
            UndineToolbarButton(
                label = sidebarStrings.menuRename,
                onClick = { state.startRename(branch) },
                modifier = Modifier.testTag(SidebarTags.MENU_RENAME),
            )
            UndineToolbarButton(
                label = sidebarStrings.menuDelete,
                onClick = { state.requestDelete(branch) },
                modifier = Modifier.testTag(SidebarTags.MENU_DELETE),
            )
            // 받기·올리기도 `refs/heads/` 를 대상으로 하는 로컬 전용 조작이다. 원격 행에 내면
            // 사용자는 원격 참조를 다룬다고 믿지만 실제 대상은 동명 로컬 브랜치가 된다.
            RemoteMenuItem(
                branch = branch,
                state = state,
                remote = remote,
                operation = BranchRemoteOperation.PULL,
                label = sidebarStrings.menuPull,
                testTag = SidebarTags.MENU_PULL,
                onRun = remote.onPull,
            )
            RemoteMenuItem(
                branch = branch,
                state = state,
                remote = remote,
                operation = BranchRemoteOperation.PUSH,
                label = sidebarStrings.menuPush,
                testTag = SidebarTags.MENU_PUSH,
                onRun = remote.onPush,
            )
        }
        MergeMenuItem(branch = branch, state = state, merge = merge)
    }
}

/**
 * 지목 받기·올리기 항목. **가용성을 사이드바가 판정하지 않는다** — 원격 작업 상태 홀더가 돌려준
 * 항목의 사유를 그대로 읽는다 (결정 D4). 그래서 눌러 본 뒤 실행에서 막히는 일이 없다.
 *
 * **막혀도 항목을 숨기지 않는다** — 사라지면 사용자는 그 브랜치에 원격 조작이 없다고 읽는다 (결정 D5).
 */
@Suppress("LongParameterList") // 항목 하나가 대상·판정·문구·실행을 한 번에 받는 자리다.
@Composable
private fun RemoteMenuItem(
    branch: Branch,
    state: SidebarState,
    remote: SidebarRemoteBinding,
    operation: BranchRemoteOperation,
    label: String,
    testTag: String,
    onRun: (Branch) -> Unit,
) {
    val contextMenuStrings = strings.contextMenu
    val entry = remote.entryOf(branch, operation)
    val text = entry.blockedReason
        ?.let { refusal -> contextMenuStrings.blockedItem(label, remoteRefusalText(refusal)) }
        ?: label

    UndineToolbarButton(
        label = text,
        onClick = {
            state.toggleMenu(branch)
            // 비활성이면 클릭이 여기까지 오지 않는다. 그래도 다시 확인해 실행이 판정을 앞서지 않게 한다.
            if (remote.entryOf(branch, operation).enabled) onRun(branch)
        },
        modifier = Modifier
            .semantics { contentDescription = text }
            .testTag(testTag),
        enabled = entry.enabled,
    )
}

/** 비활성 사유 문장. 문구는 i18n 리소스에서만 읽는다 — 화면 코드에 문자열 리터럴을 남기지 않는다. */
@Composable
private fun remoteRefusalText(refusal: BranchRemoteRefusal): String {
    val sidebarStrings = strings.sidebar
    return when (refusal) {
        BranchRemoteRefusal.NO_REMOTE -> sidebarStrings.remoteBlockedNoRemote
        BranchRemoteRefusal.NO_UPSTREAM -> sidebarStrings.remoteBlockedNoUpstream
        BranchRemoteRefusal.AMBIGUOUS_REMOTE -> sidebarStrings.remoteBlockedAmbiguousRemote
        BranchRemoteRefusal.CURRENT_BRANCH -> sidebarStrings.remoteBlockedCurrentBranch
    }
}

/**
 * 병합 항목. **가용성을 사이드바가 판정하지 않는다** — 그래프 조작의 공용 판정이 돌려준 항목의
 * 사유를 그대로 읽는다 (결정 D17). 그래서 detached HEAD 에서는 여기서도 비활성이 되고, 확인창을
 * 띄운 뒤 실행에서 실패하는 일이 없다.
 *
 * **막혀도 항목을 숨기지 않는다** — 사라지면 사용자는 병합이 없는 브랜치라고 읽는다 (결정 D3).
 */
@Composable
private fun MergeMenuItem(branch: Branch, state: SidebarState, merge: SidebarMergeBinding) {
    val contextMenuStrings = strings.contextMenu
    val label = strings.sidebar.menuMerge
    val entry = merge.entryOf(branch)
    val reason = entry?.blockedReason
    val text = reason?.let { refusal ->
        contextMenuStrings.blockedItem(label, contextMenuStrings.reason(refusal))
    } ?: label

    UndineToolbarButton(
        label = text,
        onClick = {
            state.toggleMenu(branch)
            // 비활성이면 클릭이 여기까지 오지 않는다. 그래도 다시 확인해 실행이 판정을 앞서지
            // 않게 한다 — 조작은 여기서 짓지 않고 항목이 들고 온 것을 그대로 넘긴다.
            entry?.takeIf(GraphMenuEntry::enabled)?.let { available -> merge.onRequest(available.operation) }
        },
        modifier = Modifier
            .semantics { contentDescription = text }
            .testTag(SidebarTags.MENU_MERGE),
        enabled = entry?.enabled == true,
    )
}

/**
 * 태그 행. 태그 조작(생성·삭제)은 아직 없어 행에 동작이 없다.
 *
 * **우클릭 메뉴를 붙이지 않는다** — 낼 항목이 없으므로 빈 메뉴가 열리면 사용자는 조작이 사라진
 * 것으로 읽는다 (결정 D11). 그래프의 태그 칩 우클릭이 태그 이동 하나를 낸다.
 */
@Composable
internal fun SidebarTagRow(tag: Tag) {
    val colors = UndineTokens.color

    UndineListRow(onClick = {}) {
        BasicText(
            text = tag.name.value,
            modifier = Modifier.weight(1f),
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 스태시 행. 추적되지 않는 파일까지 담은 스태시는 그 사실을 함께 알린다 —
 * pop 하지 않는 한 그 파일들이 워킹트리에 없다.
 *
 * 스태시 적용·삭제는 이 티켓 범위 밖이라 행에 동작이 없다.
 */
@Composable
internal fun SidebarStashRow(entry: StashEntry) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    UndineListRow(onClick = {}) {
        BasicText(
            text = STASH_LABEL_PREFIX + entry.index + STASH_LABEL_SUFFIX,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = entry.message,
            modifier = Modifier.weight(1f),
            style = typography.body.copy(color = colors.foregroundPrimary),
            maxLines = SINGLE_LINE,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.includedUntracked) {
            BasicText(
                text = strings.sidebar.untrackedStash,
                style = typography.caption.copy(color = colors.warning),
            )
        }
    }
}
