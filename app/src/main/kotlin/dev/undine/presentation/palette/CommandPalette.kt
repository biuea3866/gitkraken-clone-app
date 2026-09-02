package dev.undine.presentation.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.i18n.palette
import dev.undine.presentation.i18n.strings

/**
 * 커맨드 팔레트 화면 — 검색 입력과 후보 목록.
 *
 * 실행 불가 명령은 흐리게 그리고 **사유를 행 안 보조 텍스트로** 보여준다 (wave 3 결정 §UND-22).
 * 실행 중 오류로 팔레트가 닫힐 때의 알림(토스트)은 [onOutcome] 을 받는 배선(UND-26)의 몫이다.
 *
 * 열고 닫는 것은 이 컴포저블이 판단하지 않는다 — 호출부가 [CommandPaletteState.isOpen] 을 보고
 * 그릴지 정한다. 팔레트를 어떤 오버레이에 얹을지는 셸 배선이 소유한다.
 */
@Composable
fun CommandPalette(
    state: CommandPaletteState,
    modifier: Modifier = Modifier,
    onOutcome: (CommandOutcome) -> Unit = {},
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val paletteStrings = strings.palette
    val candidates = state.candidates

    Column(
        modifier = modifier
            .testTag(PaletteTags.ROOT)
            .background(colors.background),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = { state.query = it },
            placeholder = paletteStrings.searchPlaceholder,
            modifier = Modifier.fillMaxWidth().padding(spacing.medium),
        )

        when {
            !state.hasCommands -> UndineEmptyState(
                message = paletteStrings.noCommands,
                modifier = Modifier.fillMaxWidth().testTag(PaletteTags.EMPTY),
            )

            candidates.isEmpty() -> UndineEmptyState(
                message = paletteStrings.noResults,
                modifier = Modifier.fillMaxWidth().testTag(PaletteTags.EMPTY),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxWidth().testTag(PaletteTags.LIST)) {
                // key 는 안정적인 명령 id 다 — 검색어가 바뀌어 순서가 흔들려도 행이 재사용된다.
                items(items = candidates, key = { it.command.id.value }) { candidate ->
                    CommandCandidateRow(candidate) { onOutcome(state.execute(candidate.command)) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        // 안내 문구는 입력이 들어오면 사라진다 — 이름은 남아야 하므로 따로 붙인다.
        modifier = modifier.semantics { contentDescription = placeholder }.testTag(PaletteTags.QUERY),
        singleLine = true,
        textStyle = typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { field ->
            Box {
                if (query.isEmpty()) {
                    BasicText(
                        text = placeholder,
                        style = typography.body.copy(color = colors.foregroundTertiary),
                    )
                }
                field()
            }
        },
    )
}

@Composable
private fun CommandCandidateRow(candidate: CommandCandidate, onSelect: () -> Unit) {
    val blocked = candidate.availability as? CommandAvailability.Blocked

    UndineListRow(
        onClick = onSelect,
        modifier = Modifier.testTag(PaletteTags.row(candidate.command.id)),
    ) {
        CandidateLabel(candidate, blocked)
        candidate.shortcutLabel?.let { ShortcutLabel(it) }
    }
}

@Composable
private fun RowScope.CandidateLabel(candidate: CommandCandidate, blocked: CommandAvailability.Blocked?) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(modifier = Modifier.weight(1f)) {
        BasicText(
            text = candidate.command.title,
            style = typography.body.copy(
                color = if (blocked == null) colors.foregroundPrimary else colors.foregroundTertiary,
            ),
        )
        if (blocked != null) {
            BasicText(
                text = blocked.reason,
                modifier = Modifier
                    .padding(top = spacing.extraSmall)
                    .testTag(PaletteTags.reason(candidate.command.id)),
                style = typography.caption.copy(color = colors.foregroundTertiary),
            )
        }
    }
}

@Composable
private fun ShortcutLabel(label: String) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    BasicText(
        text = label,
        style = typography.caption.copy(color = colors.foregroundSecondary),
    )
}
