package dev.undine.presentation.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.undine.presentation.design.UndineTokens

/**
 * 커밋 목록·브랜치 목록처럼 선택 가능한 한 행.
 *
 * 행 안의 내용은 소비 티켓이 [content] 로 채운다 — 이 티켓은 배경·간격·선택 표시만 소유한다.
 * 최소 시그니처만 정한다 (결정 문서 UND-10).
 *
 * `LazyColumn` 에 놓을 때 `key` 를 주는 책임은 호출부에 있다 (compose-ui 규칙 3).
 */
@Composable
fun UndineListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.surface else colors.background)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
