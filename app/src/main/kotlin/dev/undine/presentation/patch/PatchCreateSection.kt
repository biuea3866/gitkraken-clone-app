package dev.undine.presentation.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.undine.domain.patch.WorkingTreeScope
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PatchStrings

/** 커밋 목록의 최대 높이. 토큰을 조합해 만든다 — 화면 고유 dp 리터럴을 두지 않는다. */
private val commitListMaxHeight: Dp
    @Composable get() = UndineTokens.spacing.huge * COMMIT_LIST_HEIGHT_STEPS

private const val COMMIT_LIST_HEIGHT_STEPS = 6
private const val SHORT_HASH_LENGTH = 7
private const val INDETERMINATE_PROGRESS = 0.5f

/**
 * 패치를 만드는 절반.
 *
 * 저장 전에 **포함될 파일과 총 크기를 먼저 보여 준다** — 의도치 않게 큰 패치를 만드는 것을 막는다.
 * 저장 대상은 형태가 정한다: 단일 통합은 파일 하나, 커밋당 파일은 디렉터리다.
 */
@Composable
internal fun PatchCreateSection(state: PatchState, copy: PatchStrings, modifier: Modifier = Modifier) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.create, style = typography.title.copy(color = colors.foregroundPrimary))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = copy.sourceWorkingTree,
                onClick = { state.selectSource(PatchSource.WORKING_TREE) },
                modifier = Modifier.testTag(PatchTags.SOURCE_WORKING_TREE),
            )
            UndineToolbarButton(
                label = copy.sourceCommits,
                onClick = { state.selectSource(PatchSource.COMMITS) },
                modifier = Modifier.testTag(PatchTags.SOURCE_COMMITS),
            )
        }
        when (state.source) {
            PatchSource.WORKING_TREE -> ScopeChoices(state, copy)
            PatchSource.COMMITS -> CommitChoices(state, copy)
        }
        FormatChoices(state, copy)
        UndineToolbarButton(
            label = copy.prepare,
            onClick = state::prepare,
            modifier = Modifier.testTag(PatchTags.PREPARE),
            enabled = state.canPrepare,
        )
        PreparationResult(state, copy)
        SaveResult(state, copy)
    }
}

/** 세 범위를 모두 노출한다. 기본값은 전체 변경이다 — 생성은 저장소를 바꾸지 않는다. */
@Composable
private fun ScopeChoices(state: PatchState, copy: PatchStrings) {
    val labels = mapOf(
        WorkingTreeScope.STAGED to copy.scopeStaged,
        WorkingTreeScope.UNSTAGED to copy.scopeUnstaged,
        WorkingTreeScope.ALL to copy.scopeAll,
    )
    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        WorkingTreeScope.entries.forEach { scope ->
            SelectableButton(
                label = labels.getValue(scope),
                selected = state.workingTreeScope == scope,
                tag = PatchTags.scope(scope.name),
                onClick = { state.selectScope(scope) },
            )
        }
    }
}

@Composable
private fun FormatChoices(state: PatchState, copy: PatchStrings) {
    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        SelectableButton(
            label = copy.formatCombined,
            selected = state.format == PatchFormat.COMBINED,
            tag = PatchTags.FORMAT_COMBINED,
            onClick = { state.selectFormat(PatchFormat.COMBINED) },
        )
        SelectableButton(
            label = copy.formatPerCommit,
            selected = state.format == PatchFormat.PER_COMMIT,
            tag = PatchTags.FORMAT_PER_COMMIT,
            onClick = { state.selectFormat(PatchFormat.PER_COMMIT) },
        )
    }
}

/**
 * 이 화면이 갖는 최근 커밋 목록.
 *
 * 2차 화면으로 열리는 독립 목적지라 그래프 화면의 선택을 물려받지 못한다 — 대신 여기서 한 페이지를
 * 읽고 **연속 구간**을 고르게 한다. 선택이 비면 생성 버튼이 막힌다.
 */
@Composable
private fun CommitChoices(state: PatchState, copy: PatchStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        BasicText(copy.commitSelectionHint, style = typography.caption.copy(color = colors.foregroundTertiary))
        when (val commits = state.commits) {
            PatchCommitsUiState.Idle, PatchCommitsUiState.Loading -> BasicText(
                copy.commitsLoading,
                style = typography.body.copy(color = colors.foregroundSecondary),
            )

            is PatchCommitsUiState.Failed -> BasicText(
                copy.commitsFailed,
                style = typography.body.copy(color = colors.deletion),
            )

            is PatchCommitsUiState.Loaded -> if (commits.commits.isEmpty()) {
                UndineEmptyState(message = copy.commitsEmpty)
            } else {
                CommitList(state, commits)
            }
        }
        if (state.selectedCommits.isEmpty()) {
            BasicText(copy.commitSelectionEmpty, style = typography.caption.copy(color = colors.warning))
        }
    }
}

@Composable
private fun CommitList(state: PatchState, commits: PatchCommitsUiState.Loaded) {
    val selected = state.selectedCommitRange
    // key 가 커밋 해시라 목록을 다시 읽어도 스크롤이 전체 재구성으로 번지지 않는다 (compose-ui 규칙 3).
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = commitListMaxHeight)
            .testTag(PatchTags.COMMITS),
    ) {
        itemsIndexed(items = commits.commits, key = { _, commit -> commit.id.value }) { index, commit ->
            UndineListRow(
                onClick = { state.extendCommitSelectionTo(index) },
                modifier = Modifier.testTag(PatchTags.commitRow(index)),
                selected = selected?.contains(index) == true,
            ) {
                BasicText(
                    commit.id.value.take(SHORT_HASH_LENGTH),
                    style = UndineTokens.typography.mono.copy(color = UndineTokens.color.accent),
                )
                BasicText(
                    commit.message.lineSequence().firstOrNull().orEmpty(),
                    style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
                )
            }
        }
    }
}

@Composable
private fun PreparationResult(state: PatchState, copy: PatchStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    when (val preparation = state.preparation) {
        PatchPreparationUiState.Idle -> Unit
        PatchPreparationUiState.Preparing -> Column {
            BasicText(copy.preparing, style = typography.body.copy(color = colors.foregroundSecondary))
            UndineProgressBar(fraction = INDETERMINATE_PROGRESS)
        }

        is PatchPreparationUiState.Failed -> BasicText(
            copy.prepareFailed,
            style = typography.body.copy(color = colors.deletion),
        )

        is PatchPreparationUiState.Ready -> IncludedContent(state, copy, preparation.preparation)
    }
}

@Composable
private fun IncludedContent(state: PatchState, copy: PatchStrings, preparation: PatchPreparation) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        BasicText(copy.includedFiles, style = typography.body.copy(color = colors.foregroundPrimary))
        Column(modifier = Modifier.testTag(PatchTags.INCLUDED_FILES)) {
            if (preparation.paths.isEmpty()) {
                BasicText(
                    copy.includedFilesEmpty,
                    style = typography.caption.copy(color = colors.foregroundSecondary),
                )
            } else {
                preparation.paths.forEach { path ->
                    BasicText(path, style = typography.mono.copy(color = colors.foregroundSecondary))
                }
            }
        }
        BasicText(
            copy.totalSize(preparation.totalBytes),
            modifier = Modifier.testTag(PatchTags.TOTAL_SIZE),
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        UndineToolbarButton(
            label = copy.save,
            onClick = state::requestSave,
            modifier = Modifier.testTag(PatchTags.SAVE),
        )
    }
}

/** 덮어쓰기는 **확인 뒤에만** 일어난다. 파괴적 동작을 조용히 하지 않는다. */
@Composable
private fun SaveResult(state: PatchState, copy: PatchStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    when (val save = state.save) {
        PatchSaveUiState.Idle -> Unit
        PatchSaveUiState.Saving -> Column(modifier = Modifier.testTag(PatchTags.SAVE_PROGRESS)) {
            BasicText(copy.saving, style = typography.body.copy(color = colors.foregroundSecondary))
            UndineProgressBar(fraction = INDETERMINATE_PROGRESS)
        }

        is PatchSaveUiState.Saved -> BasicText(
            copy.saved(save.target),
            style = typography.caption.copy(color = colors.foregroundSecondary),
        )

        is PatchSaveUiState.Failed -> Column {
            BasicText(copy.saveFailed, style = typography.body.copy(color = colors.deletion))
            // 되돌리기까지 실패했으면 폴더 상태가 어떤지 말한다 — 로그로만 남기면 사용자가 알 길이 없다.
            if (save.rollbackFailed) {
                BasicText(copy.saveRollbackFailed, style = typography.caption.copy(color = colors.deletion))
            }
            // 지우지 못한 임시 파일도 마찬가지다 — 어디에 무엇이 남았는지 말해야 사용자가 치울 수 있다.
            if (save.leftoverTemporaries.isNotEmpty()) {
                BasicText(
                    copy.saveTemporaryLeft(save.leftoverTemporaries.joinToString()),
                    style = typography.caption.copy(color = colors.deletion),
                    modifier = Modifier.testTag(PatchTags.SAVE_TEMPORARY_LEFT),
                )
            }
        }

        is PatchSaveUiState.ConfirmOverwrite -> Column(
            verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
        ) {
            BasicText(
                copy.overwriteWarning(save.existingNames.joinToString()),
                style = typography.caption.copy(color = colors.warning),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
                UndineToolbarButton(
                    label = copy.overwriteConfirm,
                    onClick = state::confirmOverwrite,
                    modifier = Modifier.testTag(PatchTags.OVERWRITE_CONFIRM),
                )
                UndineToolbarButton(
                    label = copy.overwriteCancel,
                    onClick = state::cancelOverwrite,
                    modifier = Modifier.testTag(PatchTags.OVERWRITE_CANCEL),
                )
            }
        }
    }
}

/**
 * 고른 것과 고르지 않은 것을 **색이 아니라 표식으로** 구분한다 — 토큰이 버튼 선택 상태를 갖지 않고,
 * 색만으로 가르면 대비가 낮은 테마에서 무엇이 선택됐는지 읽히지 않는다.
 */
@Composable
internal fun SelectableButton(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    UndineToolbarButton(
        label = if (selected) "● $label" else "○ $label",
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}
