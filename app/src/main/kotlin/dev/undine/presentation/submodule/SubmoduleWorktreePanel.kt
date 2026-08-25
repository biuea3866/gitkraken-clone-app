package dev.undine.presentation.submodule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.submoduleWorktree

/** 서브모듈과 worktree 상태를 그린다. I/O는 [SubmodulePanelState]·[WorktreePanelState]가 한다. */
@Composable
fun SubmoduleWorktreePanel(
    submodules: SubmodulePanelState,
    worktrees: WorktreePanelState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(UndineTokens.color.background)
            .padding(UndineTokens.spacing.small),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.medium),
    ) {
        SubmoduleSection(submodules)
        WorktreeSection(worktrees)
    }
}

@Composable
private fun SubmoduleSection(state: SubmodulePanelState) {
    val texts = strings.submoduleWorktree
    BasicText(
        texts.submodulesTitle,
        style = UndineTokens.typography.title.copy(color = UndineTokens.color.foregroundPrimary),
    )
    if (state.isEmpty) {
        UndineEmptyState(texts.submodulesEmpty)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag(SubmoduleWorktreeTags.SUBMODULE_LIST),
        ) {
            items(state.rows, key = { it.path }) { row ->
                UndineListRow(onClick = { state.requestOpen(row) }) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
                    ) {
                        BasicText(
                            row.path,
                            style = UndineTokens.typography.mono.copy(color = UndineTokens.color.foregroundPrimary),
                        )
                        BasicText(
                            submoduleStatus(row, texts),
                            style = UndineTokens.typography.caption.copy(
                                color = UndineTokens.color.foregroundSecondary,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
                            if (SubmoduleAction.INITIALIZE in row.actions) {
                                KeyboardActionButton(texts.initialize) { state.initialize(row) }
                            }
                            if (SubmoduleAction.OPEN in row.actions) {
                                KeyboardActionButton(texts.open) { state.requestOpen(row) }
                            }
                            if (SubmoduleAction.COMMIT_TO_PARENT in row.actions) {
                                KeyboardActionButton(texts.commitToParent) { state.requestCommitToParent(row) }
                            }
                            if (SubmoduleAction.UPDATE_FROM_PARENT in row.actions) {
                                KeyboardActionButton(texts.updateFromParent) { state.updateFromParent(row) }
                            }
                        }
                    }
                }
            }
        }
    }
    PanelFailure(state.failure?.message)
}

@Composable
private fun WorktreeSection(state: WorktreePanelState) {
    val texts = strings.submoduleWorktree
    BasicText(
        texts.worktreesTitle,
        style = UndineTokens.typography.title.copy(color = UndineTokens.color.foregroundPrimary),
    )
    if (state.isEmpty) {
        UndineEmptyState(texts.worktreesEmpty)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag(SubmoduleWorktreeTags.WORKTREE_LIST),
        ) {
            items(state.rows, key = { it.worktree.name }) { row ->
                WorktreeRow(row, state, texts)
            }
        }
    }
    state.dirtyRemovalPathCount?.let { count ->
        BasicText(
            texts.dirtyRemovalWarning(count),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
        )
    }
    PanelFailure(state.failure?.message)
}

@Composable
private fun WorktreeRow(
    row: WorktreeRowModel,
    state: WorktreePanelState,
    texts: dev.undine.presentation.i18n.SubmoduleWorktreeStrings,
) {
    UndineListRow(onClick = { state.requestOpen(row) }) {
        Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
            BasicText(
                row.worktree.name,
                style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
            )
            BasicText(
                row.worktree.path.value,
                style = UndineTokens.typography.mono.copy(color = UndineTokens.color.foregroundSecondary),
            )
            row.worktree.branch?.let {
                BasicText(
                    it.value,
                    style = UndineTokens.typography.caption.copy(
                        color = UndineTokens.color.foregroundSecondary,
                    ),
                )
            }
            if (row.isCurrent) {
                BasicText(
                    texts.current,
                    style = UndineTokens.typography.caption.copy(color = UndineTokens.color.accent),
                )
            }
            if (row.worktree.state == dev.undine.domain.worktree.WorktreeState.ORPHANED) {
                BasicText(
                    texts.orphaned,
                    style = UndineTokens.typography.caption.copy(color = UndineTokens.color.warning),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
                if (WorktreeAction.OPEN in row.actions) {
                    KeyboardActionButton(texts.open) { state.requestOpen(row) }
                }
                if (WorktreeAction.REMOVE in row.actions) {
                    KeyboardActionButton(texts.remove) { state.remove(row) }
                }
                if (WorktreeAction.PRUNE in row.actions) {
                    KeyboardActionButton(texts.prune) { state.prune(row) }
                }
            }
        }
    }
}

@Composable
private fun PanelFailure(detail: String?) {
    if (detail != null) {
        BasicText(
            strings.submoduleWorktree.operationFailed(detail),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.deletion),
        )
    }
}

@Composable
private fun KeyboardActionButton(label: String, action: () -> Unit) {
    UndineToolbarButton(
        label = label,
        onClick = action,
        modifier = Modifier.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp &&
                (event.key == Key.Enter || event.key == Key.Spacebar)
            ) {
                action()
                true
            } else {
                false
            }
        },
    )
}

private fun submoduleStatus(
    row: SubmoduleRowModel,
    texts: dev.undine.presentation.i18n.SubmoduleWorktreeStrings,
): String =
    when {
        !row.initialized -> texts.initialize
        row.locallyModified && row.divergedFromRecorded -> texts.modifiedAndDiverged
        row.locallyModified -> texts.locallyModified
        row.divergedFromRecorded -> texts.diverged
        else -> texts.initialized
    }

object SubmoduleWorktreeTags {
    const val SUBMODULE_LIST = "submodule-worktree-submodule-list"
    const val WORKTREE_LIST = "submodule-worktree-worktree-list"
}
