package dev.undine.presentation.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.undine.domain.DiffResult
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.diff.DEFAULT_TAB_WIDTH
import dev.undine.presentation.diff.DiffRow
import dev.undine.presentation.diff.DiffViewMode
import dev.undine.presentation.diff.SplitLineRow
import dev.undine.presentation.diff.UnifiedLineRow
import dev.undine.presentation.diff.diffRowsOf
import dev.undine.presentation.i18n.PatchStrings
import dev.undine.presentation.i18n.diff
import dev.undine.presentation.i18n.strings

/** 미리보기 목록의 최대 높이. 토큰을 조합해 만든다 — 화면 고유 dp 리터럴을 두지 않는다. */
private val previewMaxHeight: Dp
    @Composable get() = UndineTokens.spacing.huge * PREVIEW_HEIGHT_STEPS

/** 미리보기가 차지할 huge 간격의 배수. 적용 조작이 화면 밖으로 밀려나지 않는 선이다. */
private const val PREVIEW_HEIGHT_STEPS = 6

/**
 * 적용 전에 패치 내용을 읽는 **읽기 전용** 미리보기.
 *
 * `DiffViewer` 를 그대로 쓰지 않는다 — 그쪽은 hunk 마다 스테이징 버튼을 **무조건** 그리고
 * `onStageHunk` 로 내보내므로, 여기서 재사용하면 아무 일도 하지 않는 버튼이 생긴다. 대신 한 단계
 * 아래인 [diffRowsOf] 와 행 컴포저블을 그대로 써 **같은 형태**의 화면을 만든다. `presentation/diff/`
 * 는 이 화면의 소유가 아니므로 수정하지 않는다.
 *
 * 미리볼 수 없는 패치는 사유를 안내하고, 그것이 **적용을 막는 사유가 아님**을 함께 알린다.
 */
@Composable
internal fun PatchPreviewPanel(
    result: DiffResult,
    viewMode: DiffViewMode,
    copy: PatchStrings,
    onViewMode: (DiffViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(
        modifier = modifier.fillMaxWidth().testTag(PatchTags.PREVIEW),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        BasicText(copy.preview, style = typography.body.copy(color = colors.foregroundPrimary))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = copy.previewUnified,
                onClick = { onViewMode(DiffViewMode.UNIFIED) },
                modifier = Modifier.testTag(PatchTags.PREVIEW_UNIFIED),
            )
            UndineToolbarButton(
                label = copy.previewSplit,
                onClick = { onViewMode(DiffViewMode.SPLIT) },
                modifier = Modifier.testTag(PatchTags.PREVIEW_SPLIT),
            )
        }
        when (result) {
            is DiffResult.NotComputed -> NotComputedNotice(result.reason, copy)
            is DiffResult.Computed -> ComputedPreview(result, viewMode, copy)
        }
    }
}

@Composable
private fun NotComputedNotice(reason: DiffResult.Reason, copy: PatchStrings) {
    val message = when (reason) {
        DiffResult.Reason.BINARY -> copy.previewBinary
        DiffResult.Reason.TOO_LARGE -> copy.previewTooLarge
        // 패치 미리보기는 패치 텍스트에서 만들어져 이 사유가 나올 경로가 없다. 그래도 `when` 을
        // else 로 열지 않고, 문구는 diff 네임스페이스의 같은 안내를 그대로 쓴다 (UND-61).
        DiffResult.Reason.LFS_POINTER -> strings.diff.lfsObjectNotice
    }
    UndineEmptyState(
        message = message,
        modifier = Modifier.fillMaxWidth().testTag(PatchTags.PREVIEW_NOTICE),
        // 미리보기를 못 만드는 것과 적용할 수 없는 것은 다르다 — 같이 말해야 사용자가 멈추지 않는다.
        description = copy.previewStillApplicable,
    )
}

@Composable
private fun ComputedPreview(result: DiffResult.Computed, viewMode: DiffViewMode, copy: PatchStrings) {
    // 행 평탄화는 내용·모드가 바뀔 때만 한다 — 매 프레임 다시 펴면 큰 패치에서 스크롤이 끊긴다.
    val rows = remember(result.hunks, viewMode) { diffRowsOf(result.hunks, viewMode, DEFAULT_TAB_WIDTH) }
    if (rows.isEmpty()) {
        UndineEmptyState(
            message = copy.previewNoChange,
            modifier = Modifier.fillMaxWidth().testTag(PatchTags.PREVIEW_NOTICE),
        )
        return
    }
    // key 가 안정적인 행 인덱스라 스크롤이 전체 재구성으로 번지지 않는다 (compose-ui 규칙 3).
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = previewMaxHeight)) {
        items(items = rows, key = { it.key }) { row ->
            when (row) {
                // 헤더 행은 범위만 보여 준다 — 여기에는 스테이징 버튼이 없다.
                is DiffRow.HunkHeader -> HunkRangeRow(row)
                is DiffRow.Unified -> UnifiedLineRow(row)
                is DiffRow.Split -> SplitLineRow(row)
            }
        }
    }
}

/** `@@ -oldStart,oldLineCount +newStart,newLineCount @@` — Git 이 만든 값이라 번역 대상이 아니다. */
@Composable
private fun HunkRangeRow(row: DiffRow.HunkHeader) {
    BasicText(
        text = "@@ -${row.hunk.oldStart},${row.hunk.oldLineCount} " +
            "+${row.hunk.newStart},${row.hunk.newLineCount} @@",
        style = UndineTokens.typography.mono.copy(color = UndineTokens.color.foregroundSecondary),
    )
}
