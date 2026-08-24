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
import dev.undine.presentation.i18n.sidebar
import dev.undine.presentation.i18n.strings

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
    }
}

/**
 * 브랜치 한 행과 그 행에서 열리는 컨텍스트 메뉴.
 *
 * 배지 값은 [branch] 에 이미 실려 있어 **행 렌더링 중 추가 조회를 하지 않는다.**
 */
@Composable
internal fun SidebarBranchItem(
    branch: Branch,
    state: SidebarState,
    onMergeSourceSelected: (Branch) -> Unit,
) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val sidebarStrings = strings.sidebar

    Column(modifier = Modifier.fillMaxWidth()) {
        UndineListRow(
            onClick = { state.toggleMenu(branch) },
            modifier = Modifier.testTag(SidebarTags.branchRow(branch)),
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
            SidebarBranchMenu(branch = branch, state = state, onMergeSourceSelected = onMergeSourceSelected)
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
    onMergeSourceSelected: (Branch) -> Unit,
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
        }
        UndineToolbarButton(
            label = sidebarStrings.menuMerge,
            onClick = {
                state.toggleMenu(branch)
                onMergeSourceSelected(branch)
            },
            modifier = Modifier.testTag(SidebarTags.MENU_MERGE),
        )
    }
}

/** 태그 행. 태그 조작(생성·삭제)은 이 티켓 범위 밖이라 행에 동작이 없다. */
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
