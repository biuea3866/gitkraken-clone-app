package dev.undine.presentation.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.reflog.ReflogEntry
import dev.undine.domain.reflog.UnreachableCommitScan
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.i18n.RecoveryStrings
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.recovery
import dev.undine.presentation.i18n.systemStrings

/**
 * Reflog 복구와 bisect 세션을 나란히 제공하는 화면.
 *
 * 새 브랜치 이름·기존 ref 이동의 확인값은 배선 단계가 [onRecover]로 넘긴다. 이 화면은 기본 경로와
 * 경고를 명확히 선택시키고, Gateway나 JGit을 직접 호출하지 않는다.
 */
@Composable
fun RecoveryScreen(
    state: RecoveryState,
    modifier: Modifier = Modifier,
    strings: Strings = systemStrings(),
    onRecover: (ReflogEntry, RecoveryMode) -> Unit = { _, _ -> },
) {
    val copy = strings.recovery
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.G -> state.onKeyboardVerdict(BisectVerdict.GOOD)
                    Key.B -> state.onKeyboardVerdict(BisectVerdict.BAD)
                    Key.S -> state.onKeyboardVerdict(BisectVerdict.SKIP)
                    Key.R -> state.resetBisect()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .padding(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        BasicText(copy.title, style = typography.title.copy(color = colors.foregroundPrimary))
        ReflogSection(state, copy, onRecover, Modifier.fillMaxWidth().weight(1f))
        UnreachableSection(state, copy, Modifier.fillMaxWidth())
        BisectSection(state, copy, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReflogSection(
    state: RecoveryState,
    copy: RecoveryStrings,
    onRecover: (ReflogEntry, RecoveryMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.reflog, style = typography.title.copy(color = colors.foregroundPrimary))
        when (val reflog = state.reflog) {
            ReflogUiState.Idle, ReflogUiState.Loading -> BasicText(
                copy.reflogLoading,
                style = typography.body.copy(color = colors.foregroundSecondary),
            )

            is ReflogUiState.Failed -> BasicText(
                copy.loadFailed,
                style = typography.body.copy(color = colors.deletion),
            )

            is ReflogUiState.Loaded -> {
                if (reflog.entries.isEmpty()) {
                    UndineEmptyState(
                        message = copy.reflogEmpty,
                        description = if (reflog.mayBeExpired) copy.reflogExpired else null,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
                        items(reflog.entries, key = { "${it.index}:${it.to.value}" }) { entry ->
                            UndineListRow(
                                onClick = { state.selectReflog(entry) },
                                selected = state.selectedEntry == entry,
                            ) {
                                BasicText(
                                    entry.to.value.take(SHORT_HASH_LENGTH),
                                    style = typography.mono.copy(color = colors.accent),
                                )
                                BasicText(entry.action, style = typography.body.copy(color = colors.foregroundPrimary))
                            }
                        }
                    }
                }
            }
        }
        SelectedPreview(state, copy)
        val selected = state.selectedEntry
        if (selected != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                ActionButton(copy.newBranch) {
                    state.selectRecoveryMode(RecoveryMode.NewBranch)
                    onRecover(selected, RecoveryMode.NewBranch)
                }
                ActionButton(copy.moveExisting) {
                    state.selectRecoveryMode(RecoveryMode.MoveExisting)
                    onRecover(selected, RecoveryMode.MoveExisting)
                }
            }
            if (state.requiresRefMoveWarning) {
                BasicText(copy.moveWarning, style = typography.caption.copy(color = colors.warning))
            }
        }
    }
}

@Composable
private fun SelectedPreview(state: RecoveryState, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    when (val preview = state.preview) {
        PreviewUiState.Idle -> Unit
        PreviewUiState.Loading -> BasicText(
            copy.reflogLoading,
            style = typography.caption.copy(color = colors.foregroundSecondary),
        )

        is PreviewUiState.Failed -> BasicText(
            copy.loadFailed,
            style = typography.caption.copy(color = colors.deletion),
        )
        is PreviewUiState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
            BasicText(copy.preview, style = typography.body.copy(color = colors.foregroundPrimary))
            BasicText(preview.preview.commit.message, style = typography.body.copy(color = colors.foregroundSecondary))
            BasicText(copy.changedFiles, style = typography.caption.copy(color = colors.foregroundTertiary))
            preview.preview.changedFiles.forEach { file ->
                BasicText(file.path, style = typography.mono.copy(color = colors.foregroundSecondary))
            }
        }
    }
}

@Composable
private fun UnreachableSection(
    state: RecoveryState,
    copy: RecoveryStrings,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.scanWarning, style = typography.caption.copy(color = colors.warning))
        ActionButton(copy.scanUnreachable, enabled = state.unreachable !is UnreachableUiState.Scanning) {
            state.startUnreachableScan()
        }
        when (val scan = state.unreachable) {
            UnreachableUiState.NotStarted -> Unit
            UnreachableUiState.Scanning -> {
                BasicText(copy.scanning, style = typography.body.copy(color = colors.foregroundSecondary))
                UndineProgressBar(fraction = INDETERMINATE_PROGRESS)
            }

            is UnreachableUiState.Failed -> BasicText(
                copy.loadFailed,
                style = typography.body.copy(color = colors.deletion),
            )
            is UnreachableUiState.Completed -> if (scan.result is UnreachableCommitScan.NotSupported) {
                BasicText(copy.scanUnsupported, style = typography.body.copy(color = colors.warning))
            }
        }
    }
}

@Composable
private fun BisectSection(
    state: RecoveryState,
    copy: RecoveryStrings,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.bisect, style = typography.title.copy(color = colors.foregroundPrimary))
        val session = state.bisectSession
        session?.testing?.let { testing ->
            BasicText(
                copy.currentTarget(testing.value.take(SHORT_HASH_LENGTH)),
                style = typography.mono.copy(color = colors.accent),
            )
        }
        state.bisectResult?.let { result -> BisectResultText(result, copy) }
        BisectHistoryText(state.bisectHistory, copy)
        BasicText(
            copy.historyNotChronological,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            ActionButton(copy.markGood) { state.markBisect(BisectVerdict.GOOD) }
            ActionButton(copy.markBad) { state.markBisect(BisectVerdict.BAD) }
            ActionButton(copy.skip) { state.markBisect(BisectVerdict.SKIP) }
            if (state.resetVisible) {
                ActionButton(copy.reset, enabled = session != null) { state.resetBisect() }
            }
        }
        if (state.bisectFailure != null) {
            BasicText(copy.loadFailed, style = typography.caption.copy(color = colors.deletion))
        }
    }
}

@Composable
private fun BisectHistoryText(history: BisectHistoryDisplay, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    if (history.good.isNotEmpty()) {
        BasicText(
            copy.historyGood(history.good.joinToString { it.value.take(SHORT_HASH_LENGTH) }),
            style = typography.mono.copy(color = colors.addition),
        )
    }
    history.currentBad?.let { bad ->
        BasicText(
            copy.historyCurrentBad(bad.value.take(SHORT_HASH_LENGTH)),
            style = typography.mono.copy(color = colors.deletion),
        )
    }
    if (history.skipped.isNotEmpty()) {
        BasicText(
            copy.historySkipped(history.skipped.joinToString { it.value.take(SHORT_HASH_LENGTH) }),
            style = typography.mono.copy(color = colors.warning),
        )
    }
}

@Composable
private fun BisectResultText(result: BisectResult, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    when (result) {
        is BisectResult.Testing -> Column {
            BasicText(
                copy.currentTarget(result.commit.value.take(SHORT_HASH_LENGTH)),
                style = typography.mono.copy(color = colors.accent),
            )
            BasicText(
                copy.remainingCandidates(result.remainingCandidates),
                style = typography.body.copy(color = colors.foregroundSecondary),
            )
            BasicText(
                copy.remainingChecks(result.expectedRemainingChecks),
                style = typography.body.copy(color = colors.foregroundSecondary),
            )
        }

        is BisectResult.FirstBad -> BasicText(
            copy.firstBad(result.commit.value.take(SHORT_HASH_LENGTH)),
            style = typography.body.copy(color = colors.addition),
        )
        is BisectResult.Inconclusive -> Column {
            BasicText(copy.inconclusive, style = typography.body.copy(color = colors.warning))
            BasicText(
                copy.inconclusiveReason,
                style = typography.caption.copy(color = colors.foregroundSecondary),
            )
            result.candidates.forEach { candidate ->
                BasicText(
                    candidate.value.take(SHORT_HASH_LENGTH),
                    style = typography.mono.copy(color = colors.foregroundSecondary),
                )
            }
        }

        is BisectResult.ReversedRange, is BisectResult.Unsupported ->
            BasicText(copy.loadFailed, style = typography.body.copy(color = colors.warning))
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = UndineTokens.color.accent),
    ) {
        BasicText(label, style = UndineTokens.typography.body)
    }
}

private const val SHORT_HASH_LENGTH = 7
private const val INDETERMINATE_PROGRESS = 0.5f
