package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.presentation.design.UndineTokens

/**
 * 커밋 목록의 한 행 — 레인 그림 + 참조 칩 + 요약 · 작성자 · 상대 시각 · 짧은 해시.
 *
 * 레인 열에는 세로 여백을 주지 않는다 — 여백이 있으면 행 사이에서 통과선이 끊겨 보인다.
 * 그래서 [dev.undine.presentation.design.component.UndineListRow] 대신 직접 구성한다.
 */
@Composable
internal fun CommitRow(
    display: GraphRowDisplay,
    laneCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GraphLaneGeometry.ROW_HEIGHT)
            .background(if (selected) colors.surface else colors.background)
            .clickable(onClick = onClick)
            .testTag(GraphTags.row(display.item.commit.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaneCanvas(
            row = display.item.row,
            laneCount = laneCount,
            modifier = Modifier
                .width(GraphLaneGeometry.columnWidth(laneCount))
                .fillMaxHeight()
                .testTag(GraphTags.lanes(display.item.commit.id)),
        )
        Row(
            modifier = Modifier.weight(1f).padding(horizontal = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            display.chips.forEach { RefChip(chip = it) }
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
