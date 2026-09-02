package dev.undine.presentation.conflict

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.conflict.ConflictChoice
import dev.undine.domain.conflict.ConflictSide
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.conflict
import dev.undine.presentation.i18n.strings

private const val SINGLE_LINE = 1
private const val FILE_PANE_FRACTION = 0.28f
private const val SOURCE_PANE_FRACTION = 0.34f

/**
 * 충돌 해결 에디터. 왼쪽에 충돌 파일 목록, 오른쪽에 4분할(ours·base·theirs 위 / 결과 아래)이다.
 *
 * 세 원본은 **지금 보는 구간**([ConflictState.focusedRegion])의 것을 보여준다 — 파일 전체를 세 벌
 * 나란히 두면 긴 파일에서 어느 줄이 충돌인지 찾을 수 없다. 구간 이동은 위쪽 탭으로 한다.
 *
 * 결과 패널은 편집 가능하다. 편집은 그 구간의 [ConflictChoice.Edited] 가 된다.
 */
@Composable
fun ConflictEditor(
    state: ConflictState,
    modifier: Modifier = Modifier,
) {
    val texts = strings.conflict

    if (state.isClean) {
        UndineEmptyState(
            message = texts.emptyTitle,
            description = texts.emptyDescription,
            modifier = modifier.fillMaxSize().testTag(ConflictTags.EMPTY),
        )
        return
    }

    Box(modifier = modifier.fillMaxSize().background(UndineTokens.color.background)) {
        Row(modifier = Modifier.fillMaxSize().testTag(ConflictTags.ROOT)) {
            FilePane(
                state = state,
                modifier = Modifier.fillMaxWidth(FILE_PANE_FRACTION).fillMaxHeight(),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(UndineTokens.spacing.small),
                verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
            ) {
                EditorHeader(state = state)
                when {
                    state.selectedFile?.isBinary == true -> BinaryChoice(state = state)
                    // weight 로 **남은 공간만** 쓴다 — fillMaxSize 면 열 전체를 차지해 아래 안내가
                    // 화면 밖으로 밀리고, 표식 잔존 차단 이유가 보이지 않는다.
                    state.document != null -> ResolutionArea(
                        state = state,
                        modifier = Modifier.weight(1f),
                    )
                }
                EditorNotices(state = state)
            }
        }
        // 확인 대화는 **덮어 그린다** — 아래에 이어 놓으면 fillMaxSize 인 해결 영역이 공간을 다 먹어
        // 높이 0 으로 접히고, 파괴적 조작의 확인이 화면에서 사라진다.
        state.abortConfirmation?.let { confirmation ->
            ConflictAbortDialog(
                paths = confirmation.discardedPaths,
                staleReason = state.staleReason.takeIf { state.abortStale },
                onAccept = state::confirmAbort,
                onDismiss = state::dismiss,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(DIALOG_WIDTH_FRACTION),
            )
        }
    }
}

/** 충돌 파일 목록. 해결한 파일은 표시를 달아 남은 것이 무엇인지 보이게 한다. */
@Composable
private fun FilePane(state: ConflictState, modifier: Modifier) {
    val colors = UndineTokens.color
    val texts = strings.conflict

    Column(modifier = modifier.padding(UndineTokens.spacing.small)) {
        BasicText(
            text = texts.filesTitle,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(ConflictTags.FILE_LIST)) {
            items(items = state.files, key = { file -> file.path }) { file ->
                UndineListRow(
                    onClick = { state.select(file.path) },
                    selected = file.path == state.selectedPath,
                    modifier = Modifier.testTag(ConflictTags.fileRow(file.path)),
                ) {
                    BasicText(
                        text = file.path,
                        modifier = Modifier.fillMaxWidth(FILE_NAME_FRACTION),
                        style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
                        maxLines = SINGLE_LINE,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (file.path in state.resolvedPaths) {
                        BasicText(
                            text = texts.resolvedMark,
                            style = UndineTokens.typography.caption.copy(color = colors.addition),
                        )
                    }
                }
            }
        }
    }
}

/** 진행률 + 저장·계속·중단. 중단은 되돌릴 수 있음을 함께 알린다. */
@Composable
private fun EditorHeader(state: ConflictState) {
    val texts = strings.conflict

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        BasicText(
            text = texts.progress(state.resolvedRegionCount, state.regionCount),
            modifier = Modifier.testTag(ConflictTags.PROGRESS),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            // 미해결이어도 누를 수 있다 — 차단은 저장 시점에 하고 **남은 표식 위치를 알려준다**.
            // 버튼을 잠그면 어디가 남았는지 알려줄 계기가 사라진다.
            UndineToolbarButton(
                label = texts.save,
                onClick = state::save,
                enabled = state.document != null,
                modifier = Modifier.testTag(ConflictTags.SAVE),
            )
            UndineToolbarButton(
                label = texts.continueOperation,
                onClick = state::continueOperation,
                enabled = state.isClean || state.files.all { it.path in state.resolvedPaths },
                modifier = Modifier.testTag(ConflictTags.CONTINUE),
            )
            UndineToolbarButton(
                label = texts.abort,
                onClick = state::requestAbort,
                modifier = Modifier.testTag(ConflictTags.ABORT),
            )
        }
        BasicText(
            text = texts.abortNotice,
            modifier = Modifier.testTag(ConflictTags.ABORT_NOTICE),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundTertiary),
        )
    }
}

/** 구간 탭 + 세 원본 패널 + 결과 패널. */
@Composable
private fun ResolutionArea(state: ConflictState, modifier: Modifier = Modifier) {
    val texts = strings.conflict
    val focused = state.focusedConflict

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        RegionTabs(state = state)
        if (focused != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
            ) {
                SourcePane(texts.oursPane, focused.ours, ConflictTags.OURS_PANE, SOURCE_PANE_FRACTION)
                SourcePane(texts.basePane, focused.base, ConflictTags.BASE_PANE, HALF_OF_REMAINDER)
                SourcePane(texts.theirsPane, focused.theirs, ConflictTags.THEIRS_PANE, FULL_WIDTH)
            }
            RegionActions(state = state)
        }
        ResultPane(state = state)
    }
}

/** 구간 이동 탭. 구간이 하나뿐이면 굳이 그리지 않는다. */
@Composable
private fun RegionTabs(state: ConflictState) {
    if (state.regionCount <= 1) return
    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        for (index in 0 until state.regionCount) {
            UndineToolbarButton(
                label = "#${index + 1}",
                onClick = { state.focusRegion(index) },
                enabled = index != state.focusedRegion,
                modifier = Modifier.testTag(ConflictTags.regionTab(index)),
            )
        }
    }
}

/** 원본 한 패널. base 가 비어 있으면(일반 표식) 빈 채로 둔다 — 없는 것을 만들어 보여주지 않는다. */
@Composable
private fun SourcePane(title: String, lines: List<String>, tag: String, widthFraction: Float) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape

    Column(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .border(shape.borderThin, colors.divider, RoundedCornerShape(shape.cornerSmall))
            .padding(UndineTokens.spacing.extraSmall),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        BasicText(
            text = title,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        // 태그는 본문에 붙인다 — 컨테이너에 붙이면 화면 테스트가 그 패널의 내용을 읽을 수 없다.
        BasicText(
            text = lines.joinToString("\n"),
            modifier = Modifier.testTag(tag),
            style = UndineTokens.typography.mono.copy(color = colors.foregroundPrimary),
        )
    }
}

/** 이 구간을 어떻게 해결할지. */
@Composable
private fun RegionActions(state: ConflictState) {
    val texts = strings.conflict
    val index = state.focusedRegion

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        UndineToolbarButton(
            label = texts.takeOurs,
            onClick = { state.choose(index, ConflictChoice.Take(ConflictSide.OURS)) },
            modifier = Modifier.testTag(ConflictTags.TAKE_OURS),
        )
        UndineToolbarButton(
            label = texts.takeTheirs,
            onClick = { state.choose(index, ConflictChoice.Take(ConflictSide.THEIRS)) },
            modifier = Modifier.testTag(ConflictTags.TAKE_THEIRS),
        )
        UndineToolbarButton(
            label = texts.takeBoth,
            onClick = { state.choose(index, ConflictChoice.TakeBoth) },
            modifier = Modifier.testTag(ConflictTags.TAKE_BOTH),
        )
    }
}

/**
 * 결과 패널. **편집 가능하다** — 고른 구간의 내용을 직접 고쳐 쓸 수 있고, 그 편집이
 * [ConflictChoice.Edited] 가 된다. 어느 구간도 고르지 않았으면 전체 렌더 결과를 읽기 전용으로 둔다.
 */
@Composable
private fun ResultPane(state: ConflictState) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val texts = strings.conflict
    val focused = state.focusedConflict

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(shape.borderThin, colors.border, RoundedCornerShape(shape.cornerSmall))
            .padding(UndineTokens.spacing.extraSmall)
            .testTag(ConflictTags.RESULT_PANE),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        BasicText(
            text = texts.resultPane,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        if (focused == null) {
            BasicText(
                text = state.document?.render().orEmpty(),
                style = UndineTokens.typography.mono.copy(color = colors.foregroundPrimary),
            )
            return@Column
        }
        // 아직 고르지 않았으면 ours 를 편집 출발점으로 준다 — 표식을 편집창에 넣으면 그것을 지우는
        // 일이 사용자에게 다시 떨어진다.
        val editable = when (val current = focused.choice) {
            ConflictChoice.Unresolved -> focused.ours
            is ConflictChoice.Take ->
                if (current.side == ConflictSide.OURS) focused.ours else focused.theirs
            ConflictChoice.TakeBoth -> focused.ours + focused.theirs
            is ConflictChoice.Edited -> current.lines
        }
        BasicTextField(
            value = editable.joinToString("\n"),
            onValueChange = { text -> state.editRegion(state.focusedRegion, text) },
            textStyle = UndineTokens.typography.mono.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.foregroundPrimary),
            modifier = Modifier
                .fillMaxWidth()
                // 위의 창 제목은 별개 노드다 — 편집창 자신이 이름을 가져야 스크린리더가 읽는다.
                .semantics { contentDescription = texts.resultPane }
                .testTag(ConflictTags.RESULT_EDITOR),
        )
    }
}

/** 이진 파일은 합칠 수 없다 — 한쪽 선택만 제공한다. */
@Composable
private fun BinaryChoice(state: ConflictState) {
    val texts = strings.conflict

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        BasicText(
            text = texts.binaryNotice,
            modifier = Modifier.testTag(ConflictTags.BINARY_NOTICE),
            style = UndineTokens.typography.body.copy(color = UndineTokens.color.warning),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            UndineToolbarButton(
                label = texts.takeOurs,
                onClick = { state.resolveBinary(ConflictSide.OURS) },
                modifier = Modifier.testTag(ConflictTags.TAKE_OURS),
            )
            UndineToolbarButton(
                label = texts.takeTheirs,
                onClick = { state.resolveBinary(ConflictSide.THEIRS) },
                modifier = Modifier.testTag(ConflictTags.TAKE_THEIRS),
            )
        }
    }
}

/** 표식 잔존 차단·실패 안내. 둘 다 조작 결과라 함께 둔다 (중단 확인은 덮어 그리므로 최상위다). */
@Composable
private fun EditorNotices(state: ConflictState) {
    val colors = UndineTokens.color
    val texts = strings.conflict

    if (state.blockedMarkerLines.isNotEmpty()) {
        BasicText(
            text = texts.markersRemain(state.blockedMarkerLines.joinToString()),
            modifier = Modifier.testTag(ConflictTags.MARKERS_REMAIN),
            style = UndineTokens.typography.caption.copy(color = colors.warning),
        )
    }
    state.failure?.let { failure ->
        BasicText(
            text = failure.message.orEmpty(),
            modifier = Modifier.testTag(ConflictTags.FAILURE),
            style = UndineTokens.typography.caption.copy(color = colors.deletion),
        )
    }
}

private const val FILE_NAME_FRACTION = 0.75f

/** 남은 너비의 절반 — 세 패널을 균등하게 나누기 위한 비율이다. */
private const val HALF_OF_REMAINDER = 0.5f

/** 마지막 패널은 남은 너비를 전부 쓴다. */
private const val FULL_WIDTH = 1f

/** 확인 대화 폭 — 화면 가운데를 덮으면서 뒤 내용이 보이게 남긴다. */
private const val DIALOG_WIDTH_FRACTION = 0.7f
