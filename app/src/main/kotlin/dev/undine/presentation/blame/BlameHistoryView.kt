package dev.undine.presentation.blame

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.undine.domain.Commit
import dev.undine.domain.blame.LineRange
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.blame
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.time
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Blame 화면과 파일 이력 패널. App·DI 배선은 UND-51 소관이므로, 필요한 UseCase 로 만든 [state] 와
 * 커밋 상세 이동 콜백만 상위가 준다.
 */
@Composable
fun BlameHistoryView(
    target: BlameTarget,
    state: BlameHistoryState,
    onCommitSelected: (Commit) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    val scope = rememberCoroutineScope()
    val blameStrings = strings.blame
    val commits = (state.history as? FileHistoryUiState.Loaded)
        ?.entries
        ?.associate { entry -> entry.commit.id to entry.commit }
        .orEmpty()

    LaunchedEffect(target) {
        state.loadBlame(target.path, target.initialRange)
        state.loadHistory(target.path)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Ctrl+W 는 버튼과 같은 공백 무시 토글의 키보드 경로다.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.isCtrlPressed && event.key == Key.W) {
                    scope.launch { state.setIgnoreWhitespace(!state.ignoreWhitespace) }
                    true
                } else {
                    false
                }
            }
            .background(UndineTokens.color.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(UndineTokens.spacing.small),
            horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
        ) {
            UndineToolbarButton(
                label = blameStrings.ignoreWhitespace,
                onClick = { scope.launch { state.setIgnoreWhitespace(!state.ignoreWhitespace) } },
            )
        }
        when (val uiState = state.blame) {
            BlameUiState.Idle, BlameUiState.Loading -> UndineEmptyState(blameStrings.loading, Modifier.fillMaxSize())
            BlameUiState.Unsupported -> UndineEmptyState(blameStrings.unsupported, Modifier.fillMaxSize())
            is BlameUiState.Failed -> UndineEmptyState(
                blameStrings.loadFailed,
                Modifier.fillMaxSize(),
                uiState.failure.message,
            )
            is BlameUiState.Loaded -> BlameLines(
                model = BlameLinesModel(uiState.lines, commits, now),
                onCommitSelected = onCommitSelected,
                onRecurse = { commit -> scope.launch { state.recurseBefore(commit) } },
                onExpand = { range -> scope.launch { state.expandVisibleRange(range) } },
            )
        }
        FileHistoryPane(
            history = state.history,
            pendingEntry = state.pendingComparisonEntry,
            comparison = state.comparison,
            onCommitSelected = onCommitSelected,
            onHistoryEntrySelected = { entry -> scope.launch { state.selectHistoryEntry(entry) } },
        )
    }
}

/** 상위가 선택한 파일과 처음 화면에 보이는 줄 범위. */
data class BlameTarget(val path: String, val initialRange: LineRange)

private data class BlameLinesModel(
    val lines: List<dev.undine.domain.blame.BlameLine>,
    val commits: Map<dev.undine.domain.CommitId, Commit>,
    val now: Instant,
)

@Composable
private fun BlameLines(
    model: BlameLinesModel,
    onCommitSelected: (Commit) -> Unit,
    onRecurse: (Commit) -> Unit,
    onExpand: (LineRange) -> Unit,
) {
    val rows = blameRowsOf(model.lines)
    if (rows.isEmpty()) {
        UndineEmptyState(strings.blame.noLines, Modifier.fillMaxSize())
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        // 라인 번호는 파일 안에서 안정적이므로 스크롤 확장 때도 행 정체성이 유지된다.
        items(rows, key = { row -> row.line }) { row ->
            BlameLineRow(
                row,
                model.commits[row.blameLine.commit],
                model.now,
                onCommitSelected,
                onRecurse,
            )
        }
        item(key = "expand-${rows.last().line}") {
            UndineToolbarButton(
                label = strings.blame.loadMore,
                onClick = {
                    onExpand(
                        LineRange.of(rows.last().line + 1, rows.last().line + DEFAULT_EXPANSION_LINES),
                    )
                },
                modifier = Modifier.padding(UndineTokens.spacing.small),
            )
        }
    }
}

@Composable
private fun BlameLineRow(
    row: BlameRow,
    commit: Commit?,
    now: Instant,
    onCommitSelected: (Commit) -> Unit,
    onRecurse: (Commit) -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.small, vertical = spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.showCommitGutter) {
            val metadata = "${row.blameLine.commit.value.take(SHORT_HASH_LENGTH)} ${row.blameLine.author.name}"
            BasicText(
                text = metadata,
                modifier = Modifier
                    .clickable(enabled = commit != null) { commit?.let(onCommitSelected) }
                    .padding(end = spacing.small),
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
            if (commit != null) {
                BasicText(
                    text = strings.time.relative(commit.committedAt, now),
                    modifier = Modifier.padding(end = spacing.small),
                    style = UndineTokens.typography.caption.copy(color = colors.foregroundTertiary),
                )
                UndineToolbarButton(strings.blame.recurseBefore, onClick = { onRecurse(commit) })
            }
        }
        BasicText(
            text = "${row.line}  ${row.blameLine.content}",
            style = UndineTokens.typography.mono.copy(color = colors.foregroundPrimary),
        )
    }
}

@Composable
private fun FileHistoryPane(
    history: FileHistoryUiState,
    pendingEntry: dev.undine.domain.blame.FileHistoryEntry?,
    comparison: DiffUiState,
    onCommitSelected: (Commit) -> Unit,
    onHistoryEntrySelected: (dev.undine.domain.blame.FileHistoryEntry) -> Unit,
) {
    when (history) {
        FileHistoryUiState.Idle, FileHistoryUiState.Loading -> Unit
        is FileHistoryUiState.Failed -> UndineEmptyState(
            strings.blame.loadFailed,
            description = history.failure.message,
        )
        is FileHistoryUiState.Loaded -> history.entries.forEach { entry ->
            val suffix = entry.previousPath
                ?.let { previous -> " ${strings.blame.renamedFrom(previous)}" }
                .orEmpty()
            BasicText(
                text = "${entry.commit.message.lineSequence().firstOrNull().orEmpty()}$suffix",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onCommitSelected(entry.commit)
                        onHistoryEntrySelected(entry)
                    }
                    .padding(UndineTokens.spacing.small),
                style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
            )
        }
    }
    pendingEntry?.let { entry ->
        BasicText(
            text = strings.blame.comparisonStart(entry.path),
            modifier = Modifier.padding(UndineTokens.spacing.small),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary),
        )
    }
    when (comparison) {
        DiffUiState.Idle, DiffUiState.Loading -> Unit
        is DiffUiState.Failed -> UndineEmptyState(strings.blame.loadFailed, description = comparison.failure.message)
        is DiffUiState.Loaded -> BasicText(
            text = strings.blame.comparisonResult(comparison.result.hunksOrZero()),
            modifier = Modifier.padding(UndineTokens.spacing.small),
            style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
        )
    }
}

private fun dev.undine.domain.DiffResult.hunksOrZero(): Int = when (this) {
    is dev.undine.domain.DiffResult.Computed -> hunks.size
    is dev.undine.domain.DiffResult.NotComputed -> 0
}

private const val SHORT_HASH_LENGTH = 8
private const val DEFAULT_EXPANSION_LINES = 200
