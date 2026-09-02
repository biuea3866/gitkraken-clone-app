package dev.undine.presentation.staging

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.design.undineDialogSurface
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.staging
import dev.undine.presentation.i18n.strings

private const val SINGLE_LINE = 1
private const val SHORT_HASH_LENGTH = 7

/**
 * 스테이징·커밋 패널.
 *
 * 두 목록(staged·unstaged)과 커밋 메시지 입력, 커밋 버튼을 한 화면에 둔다. 커밋 버튼이 비활성이면
 * **사유를 문장으로 함께 표시**한다 — 비활성 버튼만 두면 사용자는 왜 눌리지 않는지 알 수 없다.
 *
 * 길이 가이드는 표시만 하고 강제하지 않는다. 규칙을 어길 정당한 이유가 있는 커밋이 있다.
 */
@Composable
fun StagingPanel(
    state: StagingState,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing
    val texts = strings.staging

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .padding(spacing.small)
            .testTag(StagingTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        if (state.isClean) {
            UndineEmptyState(
                message = texts.cleanTitle,
                description = texts.cleanDescription,
                modifier = Modifier.fillMaxWidth().testTag(StagingTags.EMPTY),
            )
            return@Column
        }

        FileSection(
            section = FileSectionSpec(
                title = texts.stagedTitle,
                paths = state.staged,
                side = StagingSide.STAGED,
                listTag = StagingTags.STAGED_LIST,
                actionLabel = texts.unstage,
                actionTag = StagingTags.UNSTAGE_BUTTON,
            ),
            state = state,
            onAction = state::unstageSelected,
        )
        FileSection(
            section = FileSectionSpec(
                title = texts.unstagedTitle,
                paths = state.unstaged,
                side = StagingSide.UNSTAGED,
                listTag = StagingTags.UNSTAGED_LIST,
                actionLabel = texts.stage,
                actionTag = StagingTags.STAGE_BUTTON,
            ),
            state = state,
            onAction = state::stageSelected,
        )
        CommitEditor(state = state)
        PanelNotices(state = state)
    }
}

/** amend 확인과 실패 안내. 둘 다 조작 결과라 함께 둔다. */
@Composable
private fun PanelNotices(state: StagingState) {
    state.amendConfirmation?.let { target ->
        AmendConfirmDialog(
            shortHash = target.value.take(SHORT_HASH_LENGTH),
            onAccept = state::confirmAmend,
            onDismiss = state::dismiss,
        )
    }
    state.failure?.let { failure ->
        BasicText(
            text = failure.message.orEmpty(),
            modifier = Modifier.testTag(StagingTags.FAILURE),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.deletion),
        )
    }
}

/**
 * 한쪽 목록과 그 목록의 이동 버튼.
 *
 * 선택이 없으면 버튼이 **목록 전체**를 대상으로 한다 — 아무것도 고르지 않은 채 "올리기" 를 누르는 것은
 * "전부 올리기" 로 읽는 것이 자연스럽고, 그 편이 파일마다 고르는 왕복을 줄인다.
 */
@Composable
private fun FileSection(
    section: FileSectionSpec,
    state: StagingState,
    onAction: () -> Unit,
) {
    val colors = UndineTokens.color
    val side = section.side
    val paths = section.paths
    val selected = state.selection.pathsOn(side)

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            BasicText(
                text = section.title,
                modifier = Modifier.fillMaxWidth(TITLE_WIDTH_FRACTION),
                style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
            )
            UndineToolbarButton(
                label = section.actionLabel,
                onClick = onAction,
                enabled = paths.isNotEmpty(),
                modifier = Modifier.testTag(section.actionTag),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().testTag(section.listTag)) {
            items(items = paths, key = { path -> "${side.name}:$path" }) { path ->
                UndineListRow(
                    onClick = { state.select(side, toggle(selected, path)) },
                    selected = path in selected,
                    modifier = Modifier.testTag(StagingTags.fileRow(side, path)),
                ) {
                    BasicText(
                        text = path,
                        modifier = Modifier.fillMaxWidth(),
                        style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
                        maxLines = SINGLE_LINE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 메시지 입력 + amend 토글 + 커밋 버튼. 비활성 사유를 버튼 옆에 문장으로 둔다. */
@Composable
private fun CommitEditor(state: StagingState) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val texts = strings.staging
    val blocked = state.blockedReason

    Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        BasicTextField(
            value = state.message,
            onValueChange = state::changeMessage,
            textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.foregroundPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .border(shape.borderThin, colors.border, RoundedCornerShape(shape.cornerSmall))
                .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                // 빈 입력창은 읽을 텍스트가 없어 스크린리더에 이름 없는 조작 대상으로 나온다.
                .semantics { contentDescription = texts.messagePlaceholder }
                .testTag(StagingTags.MESSAGE),
        )
        BasicText(
            text = texts.subjectLengthGuide(subjectLengthOf(state.message), SUBJECT_LENGTH_GUIDE),
            modifier = Modifier.testTag(StagingTags.SUBJECT_GUIDE),
            style = UndineTokens.typography.caption.copy(color = colors.foregroundTertiary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = texts.amend,
                onClick = { state.requestAmendMode(!state.amendRequested) },
                enabled = !state.committing,
                modifier = Modifier.testTag(StagingTags.AMEND_TOGGLE),
            )
            UndineToolbarButton(
                label = texts.commit,
                onClick = state::commit,
                enabled = blocked == null && !state.committing,
                modifier = Modifier.testTag(StagingTags.COMMIT),
            )
        }
        blocked?.let { reason ->
            BasicText(
                text = texts.blocked(reason),
                modifier = Modifier.testTag(StagingTags.BLOCKED_REASON),
                style = UndineTokens.typography.caption.copy(color = colors.warning),
            )
        }
    }
}

/** amend 확인. 대상 커밋을 보여 준 뒤에만 실행한다 — 원격에 있는 커밋을 고쳐 쓰는 것은 되돌리기 어렵다. */
@Composable
private fun AmendConfirmDialog(shortHash: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val texts = strings.staging

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .border(shape.borderThick, colors.warning, RoundedCornerShape(shape.cornerMedium))
            .undineDialogSurface(onDismiss = onDismiss)
            .padding(spacing.medium)
            .testTag(StagingTags.AMEND_DIALOG),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        BasicText(
            text = texts.amendConfirmTitle,
            style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        )
        BasicText(
            text = texts.amendConfirmTarget(shortHash),
            style = UndineTokens.typography.mono.copy(color = colors.foregroundSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = texts.amendConfirmAccept,
                onClick = onAccept,
                modifier = Modifier.testTag(StagingTags.AMEND_ACCEPT),
            )
            UndineToolbarButton(
                label = strings.common.cancel,
                onClick = onDismiss,
                modifier = Modifier.testTag(StagingTags.AMEND_CANCEL),
            )
        }
    }
}

private const val TITLE_WIDTH_FRACTION = 0.6f

/** 한 목록을 그리는 데 필요한 값 묶음. 두 목록이 같은 컴포저블을 쓰므로 차이만 담는다. */
@androidx.compose.runtime.Immutable
private data class FileSectionSpec(
    val title: String,
    val paths: List<String>,
    val side: StagingSide,
    val listTag: String,
    val actionLabel: String,
    val actionTag: String,
)

/** 제목은 첫 줄이다. 본문 길이는 가이드 대상이 아니라 줄바꿈 권고 대상이다. */
private fun subjectLengthOf(message: String): Int = message.lineSequence().firstOrNull()?.length ?: 0

/** 이미 고른 경로를 다시 누르면 선택에서 빠진다 — 다중 선택은 토글로만 만든다. */
private fun toggle(selected: Set<String>, path: String): Set<String> =
    if (path in selected) selected - path else selected + path
