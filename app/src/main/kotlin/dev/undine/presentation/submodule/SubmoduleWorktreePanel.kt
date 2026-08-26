package dev.undine.presentation.submodule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                                KeyboardActionButton(texts.initialize, enabled = !state.busy) {
                                    state.initialize(row)
                                }
                            }
                            if (SubmoduleAction.OPEN in row.actions) {
                                KeyboardActionButton(texts.open) { state.requestOpen(row) }
                            }
                            if (SubmoduleAction.COMMIT_TO_PARENT in row.actions) {
                                KeyboardActionButton(texts.commitToParent) { state.requestCommitToParent(row) }
                            }
                            if (SubmoduleAction.UPDATE_FROM_PARENT in row.actions) {
                                KeyboardActionButton(texts.updateFromParent, enabled = !state.busy) {
                                    state.updateFromParent(row)
                                }
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
    WorktreeAddForm(state, texts)
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

/**
 * worktree 추가 입력.
 *
 * 두 칸 모두 Enter 로 제출할 수 있어 마우스 없이도 추가할 수 있다 (공통 규약 4). 입력값·제출 규칙은
 * [WorktreePanelState] 가 갖고, 여기서는 그리기와 키 경로만 맡는다.
 */
@Composable
private fun WorktreeAddForm(
    state: WorktreePanelState,
    texts: dev.undine.presentation.i18n.SubmoduleWorktreeStrings,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        WorktreeDraftField(
            value = state.draftPath,
            label = texts.addPathLabel,
            testTag = SubmoduleWorktreeTags.ADD_PATH,
            enabled = !state.busy,
            onValueChange = state::updateDraftPath,
            onSubmit = state::submitAdd,
        )
        WorktreeDraftField(
            value = state.draftBranch,
            label = texts.addBranchLabel,
            testTag = SubmoduleWorktreeTags.ADD_BRANCH,
            enabled = !state.busy,
            onValueChange = state::updateDraftBranch,
            onSubmit = state::submitAdd,
        )
        KeyboardActionButton(texts.add, enabled = state.canSubmitAdd) { state.submitAdd() }
    }
}

@Composable
@Suppress("LongParameterList") // Compose 입력 필드의 값·접근성·키보드 콜백을 한 곳에서 받는다.
private fun WorktreeDraftField(
    value: String,
    label: String,
    testTag: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = UndineTokens.color
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .background(colors.surface)
            .border(UndineTokens.shape.borderThin, colors.border)
            .padding(UndineTokens.spacing.extraSmall)
            .semantics { contentDescription = label }
            .testTag(testTag)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                    onSubmit()
                    true
                } else {
                    false
                }
            },
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        singleLine = true,
    )
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
                    KeyboardActionButton(texts.remove, enabled = !state.busy) { state.remove(row) }
                }
                if (WorktreeAction.PRUNE in row.actions) {
                    KeyboardActionButton(texts.prune, enabled = !state.busy) { state.prune(row) }
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

/**
 * 마우스와 키보드가 같은 동작에 닿는 버튼 (공통 규약 4).
 *
 * [enabled] 는 클릭과 키 경로를 **함께** 잠근다 — 한쪽만 잠그면 진행 중인 변경에 키보드로
 * 두 번째 요청을 낼 수 있다.
 */
@Composable
private fun KeyboardActionButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    UndineToolbarButton(
        label = label,
        onClick = action,
        enabled = enabled,
        modifier = Modifier.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyUp) return@onKeyEvent false
            if (event.key != Key.Enter && event.key != Key.Spacebar) return@onKeyEvent false
            action()
            true
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
    const val ADD_PATH = "submodule-worktree-add-path"
    const val ADD_BRANCH = "submodule-worktree-add-branch"
}
