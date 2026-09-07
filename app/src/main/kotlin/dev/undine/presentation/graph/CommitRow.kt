package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.CommitId
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.graph
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.shell.ShellSplitDefaults
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropTarget

/**
 * 커밋 목록의 한 행 — 레인 그림 + 참조 칩 + 요약 · 작성자 · 상대 시각 · 짧은 해시.
 *
 * 레인 열에는 세로 여백을 주지 않는다 — 여백이 있으면 행 사이에서 통과선이 끊겨 보인다.
 * 그래서 [dev.undine.presentation.design.component.UndineListRow] 대신 직접 구성한다.
 *
 * 레인 열의 폭과 그릴 레인 수는 [layout] 이 정한다 — 열 수준 판정과 같은 값을 본다.
 */
@Composable
internal fun CommitRow(
    display: GraphRowDisplay,
    layout: GraphColumnLayout,
    selected: Boolean,
    onClick: () -> Unit,
    dragDropState: GraphDragDropState? = null,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphLaneGeometry.ROW_HEIGHT)
            .background(if (selected) colors.surface else colors.background)
            .graphDragSource(dragDropState) { GraphDragSource.Commit(display.item.commit.id) }
            .graphDropTarget(dragDropState) { GraphDropTarget.Commit(display.item.commit.id) }
            .clickable(onClick = onClick)
            .testTag(GraphTags.row(display.item.commit.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaneColumn(display = display, layout = layout)
        // 조절 손잡이가 놓이는 자리. 손잡이는 목록 위에 얹히므로 행이 그만큼을 비워 둔다.
        Spacer(modifier = Modifier.width(ShellSplitDefaults.SPLITTER_THICKNESS))
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            display.chips.forEach { RefChip(chip = it, dragDropState = dragDropState) }
            BasicText(
                text = display.item.summary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.body.copy(color = colors.foregroundPrimary),
            )
            BasicText(
                text = display.item.commit.author.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.caption.copy(color = colors.foregroundSecondary),
            )
            BasicText(
                text = display.relativeTime,
                maxLines = 1,
                style = typography.caption.copy(color = colors.foregroundTertiary),
            )
            // 짧은 해시는 자릿수 정렬이 의미를 가지므로 고정폭 토큰을 쓴다.
            BasicText(
                text = display.item.shortHash,
                maxLines = 1,
                style = typography.mono.copy(color = colors.foregroundTertiary),
            )
        }
    }
}

/** 이 행의 레인 열 — 표시 폭만큼의 그림과, 자기 노드가 잘렸을 때의 표식. */
@Composable
private fun LaneColumn(display: GraphRowDisplay, layout: GraphColumnLayout) {
    Box(modifier = Modifier.width(layout.width).fillMaxHeight()) {
        LaneCanvas(
            row = display.item.row,
            visibleLaneCount = layout.visibleLaneCount,
            modifier = Modifier
                .fillMaxSize()
                .testTag(GraphTags.lanes(display.item.commit.id)),
        )
        if (!layout.isLaneVisible(display.item.row.lane)) {
            HiddenLaneMarker(
                commitId = display.item.commit.id,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * 자기 노드가 표시 폭 밖에 있는 행의 가장자리 표식.
 *
 * 열 수준 안내만으로는 부족하다 — 이 행은 선도 점도 그려지지 않으므로, 표식이 없으면 사용자는
 * 그 커밋이 어디 있는지 알 수 없다 (결정 A5). 문구는 카탈로그에서만 읽는다.
 */
@Composable
private fun HiddenLaneMarker(commitId: CommitId, modifier: Modifier = Modifier) {
    val description = strings.graph.rowLaneHidden

    Box(
        modifier = modifier
            .width(UndineTokens.spacing.extraSmall)
            .fillMaxHeight()
            .background(UndineTokens.color.warning)
            .semantics { contentDescription = description }
            .testTag(GraphTags.laneHidden(commitId)),
    )
}
