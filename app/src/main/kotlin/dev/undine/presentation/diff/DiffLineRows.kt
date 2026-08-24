package dev.undine.presentation.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.undine.presentation.design.UndineTokens

/** 줄 번호 열 폭. 토큰을 조합해 만든다 — 화면 고유 dp 리터럴을 두지 않는다. */
private val lineNumberWidth: Dp
    @Composable get() = UndineTokens.spacing.huge + UndineTokens.spacing.large

/** 기호 열 폭. `+` 와 `−` 는 고정폭 서체에서 같은 폭이라 한 칸이면 충분하다. */
private val markWidth: Dp
    @Composable get() = UndineTokens.spacing.large

/** 통합 뷰 한 줄 — 원본·변경본 번호를 두 열로 두고 기호와 본문을 잇는다. */
@Composable
internal fun UnifiedLineRow(row: DiffRow.Unified) {
    // 행 태그와 본문 태그를 같은 노드에 겹치면 나중 것이 앞 것을 덮으므로 겉면과 안쪽을 나눈다.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DiffTags.row(row.key))
            .withBackground(row.cell.backgroundColor()),
    ) {
        Row(modifier = Modifier.fillMaxWidth().testTag(DiffTags.LINE)) {
            LineNumber(row.cell.oldLineNumber, DiffTags.OLD_LINE_NUMBER)
            LineNumber(row.cell.newLineNumber, DiffTags.NEW_LINE_NUMBER)
            LineContent(row.cell)
        }
    }
}

/**
 * 분할 뷰 한 줄. 왼쪽은 원본 열이라 원본 번호를, 오른쪽은 변경본 열이라 변경본 번호를 쓴다.
 * 짝이 없는 칸도 자리를 남긴다 — 빈 칸이 없으면 반대쪽 줄이 위로 밀려 좌우가 어긋난다.
 */
@Composable
internal fun SplitLineRow(row: DiffRow.Split) {
    Row(modifier = Modifier.fillMaxWidth().testTag(DiffTags.row(row.key))) {
        SplitSide(row.left, row.left?.oldLineNumber, DiffTags.OLD_LINE_NUMBER, Modifier.weight(1f))
        SplitSide(row.right, row.right?.newLineNumber, DiffTags.NEW_LINE_NUMBER, Modifier.weight(1f))
    }
}

@Composable
private fun SplitSide(cell: DiffCell?, lineNumber: Int?, lineNumberTag: String, modifier: Modifier) {
    Row(modifier = modifier.testTag(DiffTags.LINE).withBackground(cell?.backgroundColor())) {
        LineNumber(lineNumber, lineNumberTag)
        if (cell != null) LineContent(cell)
    }
}

@Composable
private fun LineNumber(number: Int?, tag: String) {
    BasicText(
        text = number?.toString().orEmpty(),
        modifier = Modifier.width(lineNumberWidth).testTag(tag),
        style = UndineTokens.typography.mono.copy(
            color = UndineTokens.color.foregroundTertiary,
            textAlign = TextAlign.End,
        ),
    )
}

@Composable
private fun RowScope.LineContent(cell: DiffCell) {
    val colors = UndineTokens.color
    val monoStyle = UndineTokens.typography.mono
    val textColor = cell.mark?.foregroundOf(colors) ?: colors.foregroundPrimary

    // 기호 열은 배경색만으로 추가·삭제를 구분하지 않기 위한 것이다 (DiffChangeMark KDoc).
    BasicText(
        text = cell.mark?.symbol.orEmpty(),
        modifier = Modifier.width(markWidth),
        style = monoStyle.copy(color = textColor),
    )
    BasicText(
        text = cell.emphasizedText(),
        modifier = Modifier.weight(1f),
        style = monoStyle.copy(color = textColor),
    )
}

@Composable
private fun DiffCell.backgroundColor(): Color? = mark?.backgroundOf(UndineTokens.color)

private fun Modifier.withBackground(color: Color?): Modifier =
    if (color == null) this else background(color)

/**
 * word-level 강조를 입힌 본문. 강조 구간은 [DiffCell.changedRanges] 를 그대로 쓴다 —
 * 무엇이 바뀌었는지는 UND-05 가 이미 계산했고 화면은 다시 계산하지 않는다.
 */
private fun DiffCell.emphasizedText(): AnnotatedString {
    if (changedRanges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        changedRanges.forEach { range ->
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
        }
    }
}
