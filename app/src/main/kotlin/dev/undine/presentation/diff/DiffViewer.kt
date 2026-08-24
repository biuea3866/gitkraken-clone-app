package dev.undine.presentation.diff

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.domain.DiffHunk
import dev.undine.domain.DiffResult
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.DiffStrings
import dev.undine.presentation.i18n.diff
import dev.undine.presentation.i18n.strings

/** 탭 폭 기본값. 설정 연동은 UND-40(설정 화면) 소관이다 (wave 3 결정 A4). */
const val DEFAULT_TAB_WIDTH: Int = 4

/**
 * 선택한 파일의 diff 를 통합·분할 두 레이아웃으로 그린다.
 *
 * **순수 표시 컴포넌트다** — 조회는 `application/diff` 의 [dev.undine.application.diff.LoadFileDiffUseCase]
 * 가 하고, hunk 스테이징은 [onStageHunk] 콜백으로만 나간다. 뷰어가 `DiffGateway`·`StagingGateway` 를
 * 직접 부르지 않는다 (architecture-layers 규칙 3, wave 3 결정 §UND-16).
 * 라인 단위 스테이징은 이 티켓 범위 밖이라 hunk 단위만 노출한다.
 *
 * @param result UND-05 가 낸 계산 결과. `NotComputed` 사유는 내용 없는 안내로 그대로 보여
 *   "변경 없음" 과 구분한다 — 빈 화면을 주면 변경이 없는 것으로 오인된다.
 * @param state 뷰 모드를 소유하는 상태 홀더. 상위가 만들어 넘긴다 (compose-ui 규칙 1).
 * @param tabWidth 탭 한 칸을 몇 칸 공백으로 펼지.
 */
@Composable
fun DiffViewer(
    result: DiffResult,
    state: DiffViewerState,
    onStageHunk: (DiffHunk) -> Unit,
    modifier: Modifier = Modifier,
    tabWidth: Int = DEFAULT_TAB_WIDTH,
) {
    val diffStrings = strings.diff

    Column(modifier = modifier.testTag(DiffTags.ROOT).background(UndineTokens.color.background)) {
        ViewModeSwitch(state, diffStrings)
        when (result) {
            is DiffResult.NotComputed -> NotComputedNotice(result.reason, diffStrings)
            is DiffResult.Computed -> ComputedDiff(
                hunks = result.hunks,
                viewMode = state.viewMode,
                tabWidth = tabWidth,
                diffStrings = diffStrings,
                onStageHunk = onStageHunk,
            )
        }
    }
}

@Composable
private fun ViewModeSwitch(state: DiffViewerState, diffStrings: DiffStrings) {
    val spacing = UndineTokens.spacing

    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        UndineToolbarButton(
            label = diffStrings.unifiedViewMode,
            onClick = { state.showViewMode(DiffViewMode.UNIFIED) },
        )
        UndineToolbarButton(
            label = diffStrings.splitViewMode,
            onClick = { state.showViewMode(DiffViewMode.SPLIT) },
        )
    }
}

@Composable
private fun NotComputedNotice(reason: DiffResult.Reason, diffStrings: DiffStrings) {
    val notice = when (reason) {
        DiffResult.Reason.BINARY -> diffStrings.binaryNotice to diffStrings.binaryDescription
        DiffResult.Reason.TOO_LARGE -> diffStrings.tooLargeNotice to diffStrings.tooLargeDescription
    }
    UndineEmptyState(
        message = notice.first,
        modifier = Modifier.fillMaxSize().testTag(DiffTags.NOTICE),
        description = notice.second,
    )
}

@Composable
private fun ComputedDiff(
    hunks: List<DiffHunk>,
    viewMode: DiffViewMode,
    tabWidth: Int,
    diffStrings: DiffStrings,
    onStageHunk: (DiffHunk) -> Unit,
) {
    // 행 평탄화는 diff·모드·탭 폭이 바뀔 때만 한다 — 매 프레임 다시 펴면 큰 파일에서 스크롤이 끊긴다.
    val rows = remember(hunks, viewMode, tabWidth) { diffRowsOf(hunks, viewMode, tabWidth) }

    if (rows.isEmpty()) {
        UndineEmptyState(
            message = diffStrings.noChangesNotice,
            modifier = Modifier.fillMaxSize().testTag(DiffTags.NOTICE),
        )
        return
    }

    // key 가 안정적인 행 인덱스라 스크롤이 전체 재구성으로 번지지 않는다 (compose-ui 규칙 3).
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(DiffTags.LINES)) {
        items(items = rows, key = { it.key }) { row ->
            when (row) {
                is DiffRow.HunkHeader -> HunkHeaderRow(row, diffStrings, onStageHunk)
                is DiffRow.Unified -> UnifiedLineRow(row)
                is DiffRow.Split -> SplitLineRow(row)
            }
        }
    }
}

@Composable
private fun HunkHeaderRow(
    row: DiffRow.HunkHeader,
    diffStrings: DiffStrings,
    onStageHunk: (DiffHunk) -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
            .testTag(DiffTags.hunkHeader(row.hunkIndex)),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = row.hunk.rangeHeader(),
            style = UndineTokens.typography.mono.copy(color = colors.foregroundSecondary),
        )
        // 실제 스테이징은 UND-17 이 소유한 상태를 거친다 — 여기서는 어떤 hunk 인지만 넘긴다.
        UndineToolbarButton(
            label = diffStrings.stageHunk,
            onClick = { onStageHunk(row.hunk) },
            modifier = Modifier.testTag(DiffTags.stageHunk(row.hunkIndex)),
        )
    }
}

/** `@@ -oldStart,oldLineCount +newStart,newLineCount @@` — Git 이 만든 값이라 번역 대상이 아니다. */
private fun DiffHunk.rangeHeader(): String =
    "@@ -$oldStart,$oldLineCount +$newStart,$newLineCount @@"
