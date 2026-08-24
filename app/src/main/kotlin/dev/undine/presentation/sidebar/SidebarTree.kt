package dev.undine.presentation.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.Branch
import dev.undine.domain.OpenedRepository
import dev.undine.domain.UndineException
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToast
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.i18n.sidebar
import dev.undine.presentation.i18n.strings

/**
 * 로컬·원격 브랜치, 태그, 스태시를 접을 수 있는 트리로 보여주는 사이드바.
 *
 * 상태는 [state] 가 소유하고 이 컴포저블은 그리기만 한다 (compose-ui 규칙 1). 목록은 안정 키를 가진
 * `LazyColumn` 이라 필터·접힘이 바뀌어도 항목 정체성이 유지된다 (규칙 3).
 *
 * @param opened 저장소를 연 결과. `currentBranch == null` 이면 detached HEAD 로 보고 트리 상단에
 *   그 사실을 명시한다. 상시 갱신 경로 배선은 UND-26 소유이고 이 화면은 **받은 값을 표시**만 한다.
 * @param onMergeSourceSelected 병합 대상으로 고른 브랜치. 실제 병합 화면(UND-21) 연결은 UND-26 이 한다.
 */
@Composable
fun SidebarTree(
    state: SidebarState,
    modifier: Modifier = Modifier,
    opened: OpenedRepository? = null,
    onMergeSourceSelected: (Branch) -> Unit = {},
) {
    val spacing = UndineTokens.spacing

    Box(modifier = modifier.background(UndineTokens.color.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (opened != null && opened.currentBranch == null) {
                DetachedHeadNotice()
            }
            SidebarFilterField(filter = state.filter, onFilterChange = state::updateFilter)
            SidebarBody(state = state, onMergeSourceSelected = onMergeSourceSelected)
        }
        state.confirmation?.let { pending ->
            SidebarConfirmationPanel(
                confirmation = pending,
                state = state,
                modifier = Modifier.align(Alignment.Center).padding(spacing.medium),
            )
        }
        state.renameTarget?.let { target ->
            SidebarRenamePanel(
                branch = target,
                state = state,
                modifier = Modifier.align(Alignment.Center).padding(spacing.medium),
            )
        }
        state.actionFailure?.let { failure ->
            ActionFailureNotice(
                failure = failure,
                modifier = Modifier.align(Alignment.BottomCenter).padding(spacing.medium),
            )
        }
    }
}

/**
 * 목록 영역. **실패를 빈 목록으로 대체하지 않는다** — 조회가 실패하면 빈 상태 안내 대신 실패 안내를
 * 띄운다 (exception-handling 규칙 7). 아직 읽지 않은 상태(Idle·Loading)에서도 "브랜치가 없다" 고
 * 말하지 않는다.
 */
@Composable
private fun ColumnScope.SidebarBody(state: SidebarState, onMergeSourceSelected: (Branch) -> Unit) {
    when (val status = state.status) {
        is SidebarStatus.Failed -> LoadFailureNotice(failure = status.cause)

        is SidebarStatus.Ready -> {
            if (state.nodes.visibleBranchCount == 0) {
                SidebarEmptyBranches(filter = state.filter)
            }
            SidebarRefList(
                state = state,
                onMergeSourceSelected = onMergeSourceSelected,
                modifier = Modifier.weight(1f),
            )
        }

        SidebarStatus.Idle, SidebarStatus.Loading -> Unit
    }
}

@Composable
private fun SidebarRefList(
    state: SidebarState,
    onMergeSourceSelected: (Branch) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth().testTag(SidebarTags.LIST)) {
        items(items = state.nodes, key = { node -> node.key }) { node ->
            when (node) {
                is SidebarNode.GroupHeader -> SidebarGroupHeaderRow(
                    header = node,
                    onToggle = { state.toggleGroup(node.group) },
                )

                is SidebarNode.BranchRow -> SidebarBranchItem(
                    branch = node.branch,
                    state = state,
                    onMergeSourceSelected = onMergeSourceSelected,
                )

                is SidebarNode.TagRow -> SidebarTagRow(tag = node.tag)
                is SidebarNode.StashRow -> SidebarStashRow(entry = node.entry)
            }
        }
    }
}

/** 브랜치 이름 필터. 입력할 때마다 상태 홀더가 목록을 좁힌다. */
@Composable
private fun SidebarFilterField(filter: String, onFilterChange: (String) -> Unit) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val label = strings.sidebar.filterLabel

    BasicTextField(
        value = filter,
        onValueChange = onFilterChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.small, vertical = spacing.small)
            .background(colors.surface)
            .border(UndineTokens.shape.borderThin, colors.border)
            .padding(spacing.small)
            .semantics { contentDescription = label }
            .testTag(SidebarTags.FILTER),
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        singleLine = true,
    )
}

/**
 * detached HEAD 안내. 사용자가 모른 채 커밋하면 어느 브랜치에도 남지 않으므로 트리 **상단**에 둔다.
 */
@Composable
private fun DetachedHeadNotice() {
    UndineToast(
        message = strings.sidebar.detachedHead,
        modifier = Modifier
            .fillMaxWidth()
            .padding(UndineTokens.spacing.small)
            .testTag(SidebarTags.DETACHED),
        tone = UndineToastTone.WARNING,
    )
}

@Composable
private fun SidebarEmptyBranches(filter: String) {
    val sidebarStrings = strings.sidebar

    UndineEmptyState(
        message = if (filter.isBlank()) sidebarStrings.emptyBranches else sidebarStrings.emptyFiltered(filter),
        modifier = Modifier.fillMaxWidth().testTag(SidebarTags.EMPTY),
    )
}

@Composable
private fun LoadFailureNotice(failure: UndineException) {
    UndineToast(
        message = strings.sidebar.loadFailed(failure.message.orEmpty()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(UndineTokens.spacing.medium)
            .testTag(SidebarTags.ERROR),
        tone = UndineToastTone.ERROR,
    )
}

@Composable
private fun ActionFailureNotice(failure: UndineException, modifier: Modifier = Modifier) {
    UndineToast(
        message = strings.sidebar.actionFailed(failure.message.orEmpty()),
        modifier = modifier.testTag(SidebarTags.ACTION_ERROR),
        tone = UndineToastTone.ERROR,
    )
}
