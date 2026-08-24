package dev.undine.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.Commit
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.search
import dev.undine.presentation.i18n.strings

/** 결과 행에 보여주는 해시 자릿수. 전체 40자는 행을 다 먹는다. */
private const val HASH_ABBREVIATION_LENGTH = 8

/**
 * 커밋 검색 화면 — 입력 축 여섯 개, 진행 표시, 점진적으로 늘어나는 결과 목록.
 *
 * 상태는 [state] 가 소유하고 이 컴포저블은 렌더링만 한다 (compose-ui 1). 조회는 [state] 가
 * `application/search` UseCase 로 하며 화면은 Gateway 를 알지 못한다.
 *
 * **위·아래 방향키와 Enter 는 패널이 먼저 가로챈다.** 입력칸에 커서가 있는 동안에도 결과를 오갈 수
 * 있어야 하기 때문이다 — 한 줄 입력칸에서 위·아래는 쓰이지 않으므로 좌우 커서 이동은 그대로 남는다.
 * Escape 는 조건을 전부 지운다 (compose-ui 8).
 *
 * 진행 표시는 [UndineProgressBar] 와 상태 문구를 함께 쓴다. 막대가 그리는 값은
 * [SearchState.scanProgress] 로, 남은 페이지가 하나뿐이라고 가정한 추정 진행률이다 — 전체 커밋 수는
 * 다 훑기 전에 알 수 없지만, 이 값은 페이지를 넘길수록 단조 증가하고 순회 중에는 1.0 에 닿지 않는다.
 */
@Composable
fun SearchPanel(
    state: SearchState,
    modifier: Modifier = Modifier,
    onCommitSelected: (Commit) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .onPreviewKeyEvent { event -> handleSearchKey(event, state, onCommitSelected) }
            .testTag(SearchTags.ROOT),
    ) {
        SearchFilters(state)
        SearchStatusLine(state)
        SearchResults(state, onCommitSelected, Modifier.fillMaxWidth().weight(1f))
    }
}

private fun handleSearchKey(
    event: KeyEvent,
    state: SearchState,
    onCommitSelected: (Commit) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionDown -> consume { state.moveHighlightBy(1) }
        Key.DirectionUp -> consume { state.moveHighlightBy(-1) }
        Key.Escape -> consume { state.clearFilters() }
        Key.Enter -> state.highlightedCommit?.let { commit -> consume { onCommitSelected(commit) } } ?: false
        else -> false
    }
}

/** 키를 처리했음을 알린다 — 처리한 키가 입력칸까지 내려가지 않아야 한다. */
private inline fun consume(action: () -> Unit): Boolean {
    action()
    return true
}

@Composable
private fun SearchFilters(state: SearchState) {
    val texts = strings.search
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.fillMaxWidth().padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        SearchFilterField(state, SearchField.MESSAGE, texts.messageLabel)
        SearchFilterField(state, SearchField.AUTHOR, texts.authorLabel)
        SearchFilterField(state, SearchField.HASH, texts.hashLabel)
        SearchFilterField(state, SearchField.PATH, texts.pathLabel)
        SearchFilterField(state, SearchField.SINCE, texts.sinceLabel, hint = texts.dateFormatHint)
        SearchFilterField(state, SearchField.UNTIL, texts.untilLabel, hint = texts.dateFormatHint)
        UndineToolbarButton(
            label = texts.clear,
            onClick = state::clearFilters,
            modifier = Modifier.testTag(SearchTags.CLEAR),
        )
    }
}

@Composable
private fun SearchFilterField(
    state: SearchState,
    field: SearchField,
    label: String,
    hint: String? = null,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    val shape = UndineTokens.shape
    val invalid = state.isInvalid(field)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        BasicText(text = label, style = typography.caption.copy(color = colors.foregroundSecondary))
        BasicTextField(
            value = state.queryOf(field),
            onValueChange = { value -> state.updateQuery(field, value) },
            singleLine = true,
            textStyle = typography.body.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = shape.borderThin,
                    color = if (invalid) colors.warning else colors.border,
                    shape = RoundedCornerShape(shape.cornerSmall),
                )
                .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                .testTag(SearchTags.field(field)),
        )
        when {
            invalid -> BasicText(
                text = strings.search.invalidDate,
                style = typography.caption.copy(color = colors.warning),
                modifier = Modifier.testTag(SearchTags.INVALID_DATE),
            )

            hint != null -> BasicText(
                text = hint,
                style = typography.caption.copy(color = colors.foregroundTertiary),
            )

            else -> Unit
        }
    }
}

/** 진행 단계를 한 줄로 알린다. 진행 중과 완료 후 0건이 서로 다른 문구여야 한다. */
@Composable
private fun SearchStatusLine(state: SearchState) {
    val texts = strings.search
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        when (state.phase) {
            SearchPhase.Idle -> Unit

            SearchPhase.Running -> {
                UndineProgressBar(
                    fraction = state.scanProgress,
                    modifier = Modifier.testTag(SearchTags.PROGRESS),
                )
                BasicText(
                    text = texts.searching,
                    style = typography.caption.copy(color = colors.foregroundSecondary),
                    modifier = Modifier.testTag(SearchTags.SEARCHING),
                )
                FoundCount(state.results.size)
            }

            SearchPhase.Completed -> FoundCount(state.results.size)

            is SearchPhase.Failed -> BasicText(
                text = texts.failed,
                style = typography.caption.copy(color = colors.warning),
                modifier = Modifier.testTag(SearchTags.FAILED),
            )
        }
    }
}

@Composable
private fun FoundCount(count: Int) {
    BasicText(
        text = strings.search.foundCount(count),
        style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary),
        modifier = Modifier.testTag(SearchTags.FOUND_COUNT),
    )
}

@Composable
private fun SearchResults(
    state: SearchState,
    onCommitSelected: (Commit) -> Unit,
    modifier: Modifier,
) {
    val texts = strings.search

    when {
        state.results.isNotEmpty() -> ResultList(state, onCommitSelected, modifier)

        state.phase == SearchPhase.Idle -> UndineEmptyState(
            message = texts.idle,
            modifier = modifier.testTag(SearchTags.IDLE_STATE),
            description = texts.idleHint,
        )

        state.phase == SearchPhase.Completed -> UndineEmptyState(
            message = texts.noResults,
            modifier = modifier.testTag(SearchTags.EMPTY_STATE),
            description = texts.noResultsHint,
        )

        // 진행 중·실패는 상태 줄이 이미 알린다 — 같은 사실을 두 번 말하지 않는다.
        else -> Unit
    }
}

@Composable
private fun ResultList(
    state: SearchState,
    onCommitSelected: (Commit) -> Unit,
    modifier: Modifier,
) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography

    LazyColumn(modifier = modifier.testTag(SearchTags.RESULT_LIST)) {
        // key 는 커밋 해시다 — 결과가 뒤에 붙을 때 앞 행이 다시 그려지지 않아야 한다 (compose-ui 3).
        itemsIndexed(items = state.results, key = { _, commit -> commit.id.value }) { index, commit ->
            UndineListRow(
                onClick = {
                    state.highlightAt(index)
                    onCommitSelected(commit)
                },
                modifier = Modifier.testTag(SearchTags.row(commit.id)),
                selected = index == state.highlightedIndex,
            ) {
                BasicText(
                    text = commit.id.value.take(HASH_ABBREVIATION_LENGTH),
                    style = typography.mono.copy(color = colors.foregroundTertiary),
                )
                BasicText(
                    text = commit.message.lineSequence().first(),
                    style = typography.body.copy(color = colors.foregroundPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = commit.author.name,
                    style = typography.caption.copy(color = colors.foregroundSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
