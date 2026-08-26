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
                // 기본 경로가 아닌 이동은 이 클릭에서 실행하지 않는다 — 경고 메뉴만 연다.
                ActionButton(copy.moveExisting) { state.requestRefMove() }
            }
            if (state.requiresRefMoveWarning) {
                RefMoveConfirmation(state, copy, selected, onRecover)
            }
        }
        // 복구는 적용됐는데 Undo 항목만 없어진 경우다. 알리지 않으면 사용자는 되돌릴 수 있다고 믿는다.
        if (state.recoveryUndoRecordFailure != null) {
            BasicText(copy.undoRecordFailed, style = typography.caption.copy(color = colors.warning))
        }
    }
}

/**
 * 기존 ref 이동의 경고·확인 메뉴.
 *
 * 실행은 오직 확인 버튼에서만 일어난다. 목록 옆 버튼 한 번으로 ref 가 움직이면 잃은 커밋을 되찾으러
 * 온 사용자가 다른 커밋을 새로 잃는다.
 */
@Composable
private fun RefMoveConfirmation(
    state: RecoveryState,
    copy: RecoveryStrings,
    selected: ReflogEntry,
    onRecover: (ReflogEntry, RecoveryMode) -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    BasicText(copy.moveWarning, style = typography.caption.copy(color = colors.warning))
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
        ActionButton(copy.moveConfirm) {
            state.confirmRefMove()
            onRecover(selected, RecoveryMode.MoveExisting)
        }
        ActionButton(copy.moveCancel) { state.cancelRefMove() }
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
internal fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = UndineTokens.color.accent),
    ) {
        BasicText(label, style = UndineTokens.typography.body)
    }
}

internal const val SHORT_HASH_LENGTH = 7
private const val INDETERMINATE_PROGRESS = 0.5f
