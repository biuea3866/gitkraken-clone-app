package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.graph
import dev.undine.presentation.i18n.strings

/** 칩 배경의 불투명도. 글자 대비를 지키려면 옅어야 한다 — 색은 식별용이고 글자가 정보다. */
private const val CHIP_FILL_ALPHA = 0.18f

/**
 * 커밋 행에 붙는 참조 칩 — HEAD·브랜치·태그.
 *
 * 색은 종류로 갈린다: HEAD 는 강조색, 태그는 경고색, 브랜치는 본문색이다. 값은 전부 디자인 토큰이며
 * 여기에 색 리터럴을 두지 않는다 (compose-ui 규칙 5).
 *
 * 브랜치·태그 라벨은 참조 이름 그대로이고, **HEAD 라벨은 `graph.head` 번역에서 읽는다** —
 * 표시 문자열을 하드코딩하거나 호출부에서 받아오지 않는다.
 */
@Composable
internal fun RefChip(
    chip: GraphRefChip,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val accent = chipColor(chip.kind)

    BasicText(
        text = chip.refName ?: strings.graph.head,
        modifier = modifier
            .clip(RoundedCornerShape(shape.cornerSmall))
            // 칩 색을 옅게 깐 배경 + 같은 색 경계. 외곽선만 두면 칩이 배경에 묻혀 참조가 눈에 안 띈다.
            .background(accent.copy(alpha = CHIP_FILL_ALPHA))
            .border(shape.borderThin, accent, RoundedCornerShape(shape.cornerSmall))
            .padding(horizontal = spacing.extraSmall),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = UndineTokens.typography.caption.copy(color = accent),
    )
}

@Composable
private fun chipColor(kind: GraphRefKind): Color = when (kind) {
    GraphRefKind.HEAD -> UndineTokens.color.accent
    GraphRefKind.BRANCH -> UndineTokens.color.foregroundSecondary
    GraphRefKind.TAG -> UndineTokens.color.warning
}
