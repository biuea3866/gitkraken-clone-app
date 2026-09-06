package dev.undine.presentation.patch

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.UnsupportedReason
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PatchStrings

private const val INDETERMINATE_PROGRESS = 0.5f

/**
 * 패치를 적용하는 절반.
 *
 * 적용 전에 dry-run 결과를 **먼저 보여 준다.** 충돌·미지원·거부는 사유와 경로를 남기고 적용을 막는다 —
 * 3-way 선택지는 제공하지 않는다 (게이트웨이가 3-way 를 제공하지 않으므로 고를 대상이 없다).
 */
@Composable
internal fun PatchApplySection(state: PatchState, copy: PatchStrings, modifier: Modifier = Modifier) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.apply, style = typography.title.copy(color = colors.foregroundPrimary))
        UndineToolbarButton(
            label = copy.chooseFile,
            onClick = state::choosePatchFile,
            modifier = Modifier.testTag(PatchTags.CHOOSE_FILE),
        )
        BasicText(copy.dropHint, style = typography.caption.copy(color = colors.foregroundTertiary))
        IntakeNotice(state, copy)
        state.selectedPatch?.let { patch ->
            BasicText(
                copy.selectedPatch(patch.name),
                style = typography.caption.copy(color = colors.foregroundSecondary),
            )
        }
        OutcomeNotice(
            state = state.dryRun,
            copy = copy,
            tag = PatchTags.DRY_RUN,
            running = copy.dryRunRunning,
            // dry-run 의 Applied 는 아직 적용된 것이 아니라 **바뀔** 파일이다.
            appliedTitle = copy.changedFiles,
        )
        ApplyModeChoices(state, copy)
        CommitInputs(state, copy)
        UndineToolbarButton(
            label = copy.apply,
            onClick = state::apply,
            modifier = Modifier.testTag(PatchTags.APPLY),
            enabled = state.canApply,
        )
        OutcomeNotice(
            state = state.applyResult,
            copy = copy,
            tag = PatchTags.APPLY_RESULT,
            running = copy.applying,
            appliedTitle = copy.applied,
        )
        state.selectedPatch?.let { patch ->
            PatchPreviewPanel(
                result = patch.preview,
                viewMode = state.previewViewMode,
                copy = copy,
                onViewMode = state::showPreviewViewMode,
            )
        }
    }
}

/**
 * 패치를 받아들이는 동안의 안내 — 드롭 거부·읽는 중·읽기 실패.
 *
 * 드롭 거부는 실패가 아니라 안내다 — 무엇을 왜 안 받았는지 말하고 멈춘다. 읽는 중을 표시하는 이유는
 * 큰 패치의 읽기·파싱이 한동안 걸리기 때문이다 — 표시가 없으면 아무 반응 없는 화면으로 보인다.
 */
@Composable
private fun IntakeNotice(state: PatchState, copy: PatchStrings) {
    val typography = UndineTokens.typography
    val colors = UndineTokens.color
    state.dropRejection?.let { reason ->
        val message = when (reason) {
            PatchDrop.Reason.NO_FILE -> copy.dropRejectedEmpty
            PatchDrop.Reason.MULTIPLE_FILES -> copy.dropRejectedMultiple
        }
        BasicText(message, style = typography.caption.copy(color = colors.warning))
    }
    Column(modifier = Modifier.fillMaxWidth().testTag(PatchTags.READ_NOTICE)) {
        when (state.patchRead) {
            PatchReadUiState.Reading -> {
                BasicText(copy.readingPatch, style = typography.body.copy(color = colors.foregroundSecondary))
                UndineProgressBar(fraction = INDETERMINATE_PROGRESS)
            }

            // 읽지 못한 것을 "빈 패치" 로 접지 않는다 — 그러면 화면이 변경 없음으로 안내한다.
            is PatchReadUiState.Failed -> BasicText(
                copy.readFailed,
                style = typography.body.copy(color = colors.deletion),
            )

            PatchReadUiState.Idle, is PatchReadUiState.Loaded -> Unit
        }
    }
}

/** 기본값은 가장 안전한 워킹트리만이다 — 사용자가 결과를 확인한 뒤 직접 스테이징하면 된다. */
@Composable
private fun ApplyModeChoices(state: PatchState, copy: PatchStrings) {
    val labels = mapOf(
        PatchApplyModeChoice.WORKING_TREE_ONLY to copy.modeWorkingTree,
        PatchApplyModeChoice.INDEX to copy.modeIndex,
        PatchApplyModeChoice.CREATE_COMMIT to copy.modeCommit,
    )
    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        PatchApplyModeChoice.entries.forEach { choice ->
            SelectableButton(
                label = labels.getValue(choice),
                selected = state.applyMode == choice,
                tag = PatchTags.mode(choice.name),
                onClick = { state.selectApplyMode(choice) },
            )
        }
    }
}

/**
 * 커밋 모드의 작성자·메시지.
 *
 * **앱이 채우지 않는다.** 패치에 메타데이터가 있으면 그것을 읽기 전용으로 보여 주고, 없으면 빈 입력에서
 * 시작한다 — 기존 git 설정 값을 미리 채우면 되돌리기 비싼 커밋이 잘못된 이름으로 쌓인다.
 */
@Composable
private fun CommitInputs(state: PatchState, copy: PatchStrings) {
    if (state.applyMode != PatchApplyModeChoice.CREATE_COMMIT) return
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val metadata = state.selectedPatch?.metadata

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        if (state.hasPatchCommitMetadata) {
            Column(modifier = Modifier.testTag(PatchTags.COMMIT_METADATA)) {
                BasicText(copy.fromPatchMetadata, style = typography.caption.copy(color = colors.foregroundTertiary))
                metadata?.author?.let { author ->
                    BasicText(
                        "${author.name} <${author.email}>",
                        style = typography.mono.copy(color = colors.foregroundSecondary),
                    )
                }
                metadata?.message?.let { message ->
                    BasicText(message, style = typography.body.copy(color = colors.foregroundSecondary))
                }
            }
            return@Column
        }
        BasicText(copy.commitInputRequired, style = typography.caption.copy(color = colors.warning))
        PatchTextField(state.commitAuthorName, copy.commitAuthorName, PatchTags.COMMIT_AUTHOR_NAME) {
            state.changeCommitAuthorName(it)
        }
        PatchTextField(state.commitAuthorEmail, copy.commitAuthorEmail, PatchTags.COMMIT_AUTHOR_EMAIL) {
            state.changeCommitAuthorEmail(it)
        }
        PatchTextField(state.commitMessage, copy.commitMessage, PatchTags.COMMIT_MESSAGE) {
            state.changeCommitMessage(it)
        }
    }
}

@Composable
private fun PatchTextField(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .border(shape.borderThin, colors.border, RoundedCornerShape(shape.cornerSmall))
            .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
            // 빈 입력창은 읽을 텍스트가 없어 스크린리더에 이름 없는 조작 대상으로 나온다.
            .semantics { contentDescription = label }
            .testTag(tag),
    )
}

/**
 * dry-run 과 apply 를 **같은 매핑**으로 그린다.
 *
 * 게이트웨이가 두 경로에 같은 [ApplyOutcome] 을 주므로 화면도 결과를 한 곳에서 읽는다 —
 * "검사는 통과했는데 적용은 실패" 를 화면이 새로 만들어내지 않는다.
 */
@Composable
private fun OutcomeNotice(
    state: PatchOutcomeUiState,
    copy: PatchStrings,
    tag: String,
    running: String,
    appliedTitle: String,
) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    Column(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        when (state) {
            PatchOutcomeUiState.Idle -> Unit
            PatchOutcomeUiState.Running -> {
                BasicText(running, style = typography.body.copy(color = colors.foregroundSecondary))
                UndineProgressBar(fraction = INDETERMINATE_PROGRESS)
            }

            is PatchOutcomeUiState.Failed -> BasicText(
                copy.dryRunFailed,
                style = typography.body.copy(color = colors.deletion),
            )

            // 경로 이탈·복원 불가 — 게이트웨이가 결과가 아니라 거부로 답한 경우다.
            is PatchOutcomeUiState.Refused -> BasicText(
                copy.blockedStateViolation(state.violation.detail),
                style = typography.body.copy(color = colors.deletion),
            )

            is PatchOutcomeUiState.Completed -> OutcomeDetail(state.outcome, copy, appliedTitle)
        }
    }
}

@Composable
private fun OutcomeDetail(outcome: ApplyOutcome, copy: PatchStrings, appliedTitle: String) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    when (outcome) {
        is ApplyOutcome.Applied -> PathList(appliedTitle, outcome.paths, colors.foregroundPrimary)
        is ApplyOutcome.Conflicted -> PathList(copy.conflicted, outcome.paths, colors.deletion)
        is ApplyOutcome.Unsupported -> BasicText(
            when (outcome.reason) {
                UnsupportedReason.THREE_WAY_REQUIRED -> copy.unsupportedThreeWay
                UnsupportedReason.REVERSE_NOT_PURE_HUNK -> copy.unsupportedReverse
            },
            style = typography.body.copy(color = colors.warning),
        )

        ApplyOutcome.NoChange -> BasicText(
            copy.noChange,
            style = typography.body.copy(color = colors.foregroundSecondary),
        )
    }
}

@Composable
private fun PathList(title: String, paths: List<String>, titleColor: Color) {
    val typography = UndineTokens.typography
    Column {
        BasicText(title, style = typography.body.copy(color = titleColor))
        paths.forEach { path ->
            BasicText(
                path,
                style = typography.mono.copy(color = UndineTokens.color.foregroundSecondary),
            )
        }
    }
}
