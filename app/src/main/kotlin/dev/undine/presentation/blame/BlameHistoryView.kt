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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
    val listState = rememberLazyListState()

    LaunchedEffect(target) {
        state.loadBlame(target.path, target.initialRange)
        state.loadHistory(target.path)
    }
    LaunchedEffect(state, listState) { state.loadWhenScrolledToBottom(listState) }

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
                model = BlameLinesModel(uiState.lines, now),
                listState = listState,
                onCommitSelected = onCommitSelected,
                onRecurse = { commit -> scope.launch { state.recurseBefore(commit) } },
                onExpand = { scope.launch { state.loadNextLineRange() } },
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

/**
 * 목록 하단에 도달하면 다음 줄 범위를 이어 읽는다 — blame 은 비싸서 보이는 구간만 먼저 읽기 때문이다.
 *
 * 마지막으로 보이는 인덱스와 **쌓인 행 수**를 함께 관찰한다. 이어 읽은 범위가 뷰포트를 채우지 못하면
 * 인덱스가 그대로여도 다시 판정해야 한다. 같은 범위를 두 번 부르지 않게 막는 것은
 * [BlameHistoryState.loadNextLineRange] 안의 진행 중·파일 끝 가드다.
 */
private suspend fun BlameHistoryState.loadWhenScrolledToBottom(listState: LazyListState) {
    snapshotFlow {
        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount
    }.collect { (lastVisibleIndex, loadedCount) ->
        if (lastVisibleIndex != null && loadedCount > 0 && lastVisibleIndex >= loadedCount - 1) {
            loadNextLineRange()
        }
    }
}

private data class BlameLinesModel(val lines: List<dev.undine.domain.blame.BlameLine>, val now: Instant)

@Composable
private fun BlameLines(
    model: BlameLinesModel,
    listState: LazyListState,
    onCommitSelected: (Commit) -> Unit,
    onRecurse: (Commit) -> Unit,
    onExpand: () -> Unit,
) {
    val rows = blameRowsOf(model.lines)
    if (rows.isEmpty()) {
        UndineEmptyState(strings.blame.noLines, Modifier.fillMaxSize())
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        // 라인 번호는 파일 안에서 안정적이므로 스크롤 확장 때도 행 정체성이 유지된다.
        items(rows, key = { row -> row.line }) { row ->
            BlameLineRow(row, model.now, onCommitSelected, onRecurse)
        }
        // 스크롤과 같은 일을 하는 키보드 경로다 — 스크롤이 확장의 유일한 수단이 되지 않게 남긴다.
        item(key = EXPAND_ITEM_KEY) {
            UndineToolbarButton(
                label = strings.blame.loadMore,
                onClick = onExpand,
                modifier = Modifier.padding(UndineTokens.spacing.small),
            )
        }
    }
}

@Composable
private fun BlameLineRow(
    row: BlameRow,
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
            // 커밋은 blame 결과가 직접 들고 있다 — 파일 이력 조회 결과와 무관하게 항상 있다.
            val commit = row.blameLine.commit
            val metadata = "${commit.id.value.take(SHORT_HASH_LENGTH)} ${row.blameLine.author.name}"
            BasicText(
                text = metadata,
                modifier = Modifier
                    .clickable { onCommitSelected(commit) }
                    .padding(end = spacing.small),
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
            BasicText(
                text = strings.time.relative(commit.committedAt, now),
                modifier = Modifier.padding(end = spacing.small),
                style = UndineTokens.typography.caption.copy(color = colors.foregroundTertiary),
            )
            UndineToolbarButton(strings.blame.recurseBefore, onClick = { onRecurse(commit) })
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

/** 확장 버튼 행의 고정 key — 줄 번호 key 와 섞이지 않아야 스크롤 관찰이 흔들리지 않는다. */
private const val EXPAND_ITEM_KEY = "blame-expand"
