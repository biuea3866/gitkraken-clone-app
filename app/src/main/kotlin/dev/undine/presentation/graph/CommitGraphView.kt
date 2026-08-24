package dev.undine.presentation.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.domain.Commit
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.graph
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.time
import java.time.Instant

/**
 * 커밋 그래프 화면 — 레인 그래프가 붙은 커밋 목록.
 *
 * **DI 조립을 하지 않는다.** 필요한 것은 전부 파라미터로 받고, 셸·상세 패널과의 연결은
 * [onCommitSelected] 콜백까지만 열어 둔다 (wave 3 결정 §UND-14). 이 화면은 상세 패널을 모른다.
 *
 * 이력 로딩은 [GraphViewState] 가 application 경계([dev.undine.application.graph.LoadCommitHistoryUseCase])
 * 를 통해서만 한다 — 여기서 Gateway 를 잡거나 JGit 을 부르지 않는다.
 *
 * @param now 상대 시각의 기준 시각. 내부에서 `Instant.now()` 를 부르지 않으므로 표시가 결정적이다.
 * @param refIndex 커밋에 붙일 HEAD·브랜치·태그 칩. 참조를 아직 모르면 [CommitRefIndex.EMPTY] 다.
 */
@Composable
fun CommitGraphView(
    state: GraphViewState,
    now: Instant,
    modifier: Modifier = Modifier,
    refIndex: CommitRefIndex = CommitRefIndex.EMPTY,
    onCommitSelected: (Commit) -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state) { state.loadInitialPage() }
    LaunchedEffect(state, listState) { state.loadWhenScrolledToBottom(listState) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .testTag(GraphTags.ROOT),
    ) {
        val status = state.status
        when {
            status is GraphLoadStatus.Failed -> GraphStatusMessage(GraphTags.ERROR, failure = true)
            state.rows.isNotEmpty() -> CommitList(state, listState, refIndex, now, onCommitSelected)
            status == GraphLoadStatus.Loaded -> GraphStatusMessage(GraphTags.EMPTY, failure = false)
            else -> GraphLoadingMessage()
        }
    }
}

/**
 * 목록 하단에 도달하면 다음 페이지를 요청한다.
 *
 * 마지막으로 보이는 인덱스와 **쌓인 행 수**를 함께 관찰한다 — 페이지가 붙어 행 수만 늘어난
 * 경우에도 다시 판정해야 목록이 뷰포트를 채울 때까지 이어서 불러온다.
 * 같은 offset 을 두 번 부르지 않게 막는 것은 [GraphViewState] 안의 진행 중 요청 가드다.
 */
private suspend fun GraphViewState.loadWhenScrolledToBottom(listState: LazyListState) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to rows.size }
        .collect { (lastVisibleIndex, loadedCount) ->
            if (lastVisibleIndex != null && loadedCount > 0 && lastVisibleIndex >= loadedCount - 1) {
                loadNextPage()
            }
        }
}

@Composable
private fun CommitList(
    state: GraphViewState,
    listState: LazyListState,
    refIndex: CommitRefIndex,
    now: Instant,
    onCommitSelected: (Commit) -> Unit,
) {
    val currentStrings = strings
    val laneCount = state.laneCount
    val selectedCommitId = state.selectedCommitId

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(GraphTags.LIST),
    ) {
        // key 는 커밋 해시다 — 안정적이지 않으면 스크롤마다 목록 전체가 재구성된다 (compose-ui 규칙 3).
        items(items = state.rows, key = { item -> item.commit.id.value }) { item ->
            val display = remember(item, refIndex, now, currentStrings) {
                displayOf(item, refIndex, now, currentStrings)
            }
            CommitRow(
                display = display,
                laneCount = laneCount,
                selected = item.commit.id == selectedCommitId,
                onClick = {
                    state.selectCommit(item.commit.id)
                    onCommitSelected(item.commit)
                },
            )
        }
    }
}

private fun displayOf(
    item: GraphRowItem,
    refIndex: CommitRefIndex,
    now: Instant,
    strings: Strings,
): GraphRowDisplay = GraphRowDisplay(
    item = item,
    chips = refIndex.chipsFor(item.commit.id),
    relativeTime = strings.time.relative(item.commit.committedAt, now),
)

/**
 * 빈 이력과 로딩 실패 안내. 두 상태는 **서로 다른 태그와 문구**를 쓴다 — 실패를 "커밋이 없다"로
 * 보이게 하면 사용자가 저장소가 비었다고 오해한다 (exception-handling 규칙 7).
 */
@Composable
private fun GraphStatusMessage(tag: String, failure: Boolean) {
    val graphStrings = strings.graph

    Box(
        modifier = Modifier.fillMaxSize().testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        UndineEmptyState(
            message = if (failure) graphStrings.errorTitle else graphStrings.emptyTitle,
            description = if (failure) graphStrings.errorDescription else graphStrings.emptyDescription,
        )
    }
}

@Composable
private fun GraphLoadingMessage() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(GraphTags.LOADING),
        contentAlignment = Alignment.Center,
    ) {
        UndineEmptyState(message = strings.graph.loading)
    }
}
