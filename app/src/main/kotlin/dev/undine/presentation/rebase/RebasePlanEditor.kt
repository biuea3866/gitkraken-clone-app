package dev.undine.presentation.rebase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlanViolation
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.rebase
import dev.undine.presentation.i18n.strings

/**
 * 대화형 리베이스 계획 편집기.
 *
 * **적용을 누를 때까지 저장소를 건드리지 않는다** — 재정렬·동작 지정·메시지 편집은 모두 불변
 * `RebasePlan` 을 새 값으로 바꾸는 일이고, 화면에도 그 사실을 문구로 남긴다.
 */
@Composable
fun RebasePlanEditor(state: RebasePlanState, modifier: Modifier = Modifier) {
    val texts = strings.rebase
    val plan = state.plan

    if (plan == null || state.isEmpty) {
        UndineEmptyState(
            message = texts.emptyTitle,
            description = texts.emptyDescription,
            modifier = modifier.fillMaxSize().testTag(RebaseTags.EMPTY),
        )
        return
    }

    // 이벤트 묶음은 계획이 바뀔 때마다 새로 만들 이유가 없다 — 상태 홀더 메서드 참조만 담는다.
    val events = remember(state) {
        RebaseRowEvents(
            onMove = state::move,
            onAction = state::setAction,
            onReword = state::reword,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .padding(UndineTokens.spacing.small)
            .testTag(RebaseTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        EditorHeader(state = state)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(RebaseTags.PLAN_LIST),
            verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
        ) {
            itemsIndexed(items = plan.steps, key = { _, step -> step.commit.id.value }) { index, step ->
                RebasePlanRow(
                    index = index,
                    step = step,
                    stepCount = plan.steps.size,
                    events = events,
                )
            }
        }
        RebasePreviewPanel(entries = state.preview)
        EditorNotices(state = state)
    }
}

/** 제목·무변경 안내·적용/취소. 적용은 규칙을 지킨 계획에서만 눌린다. */
@Composable
private fun EditorHeader(state: RebasePlanState) {
    val colors = UndineTokens.color
    val texts = strings.rebase

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        BasicText(
            text = texts.planTitle,
            style = UndineTokens.typography.title.copy(color = colors.foregroundPrimary),
        )
        BasicText(
            text = texts.planHint,
            modifier = Modifier.testTag(RebaseTags.HINT),
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
            UndineToolbarButton(
                label = texts.apply,
                onClick = state::apply,
                enabled = state.canApply,
                modifier = Modifier.testTag(RebaseTags.APPLY),
            )
            UndineToolbarButton(
                label = texts.discard,
                onClick = state::discard,
                modifier = Modifier.testTag(RebaseTags.DISCARD),
            )
        }
        state.progress?.let { progress ->
            BasicText(
                text = texts.progress(progress.applied, progress.total),
                modifier = Modifier.testTag(RebaseTags.PROGRESS),
                style = UndineTokens.typography.caption.copy(color = colors.accent),
            )
        }
    }
}

/** 위반 사유·경고·결과·실패. 전부 계획 편집과 적용의 결과라 함께 둔다. */
@Composable
private fun EditorNotices(state: RebasePlanState) {
    val colors = UndineTokens.color
    val texts = strings.rebase

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        state.violations.forEach { violation ->
            BasicText(
                text = violation.describe(),
                modifier = Modifier.testTag(RebaseTags.VIOLATION),
                style = UndineTokens.typography.caption.copy(color = colors.deletion),
            )
        }
        if (state.rewritesPushedCommits) {
            BasicText(
                text = texts.pushedWarning,
                modifier = Modifier.testTag(RebaseTags.PUSHED_WARNING),
                style = UndineTokens.typography.caption.copy(color = colors.warning),
            )
        }
        if (state.stopsDuringRun) {
            BasicText(
                text = texts.stopsWarning,
                modifier = Modifier.testTag(RebaseTags.STOPS_WARNING),
                style = UndineTokens.typography.caption.copy(color = colors.warning),
            )
        }
        state.outcome?.let { outcome ->
            BasicText(
                text = outcome.describe(),
                modifier = Modifier.testTag(RebaseTags.OUTCOME),
                style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            )
        }
        state.failure?.let { failure ->
            BasicText(
                text = failure.message.orEmpty(),
                modifier = Modifier.testTag(RebaseTags.FAILURE),
                style = UndineTokens.typography.caption.copy(color = colors.deletion),
            )
        }
    }
}

@Composable
private fun RebasePlanViolation.describe(): String = when (this) {
    RebasePlanViolation.FirstStepCannotAbsorb -> strings.rebase.violationFirstAbsorb
    RebasePlanViolation.EverythingDropped -> strings.rebase.violationAllDropped
}

@Composable
private fun InteractiveRebaseOutcome.describe(): String = when (this) {
    InteractiveRebaseOutcome.Completed -> strings.rebase.outcomeCompleted
    InteractiveRebaseOutcome.NothingToDo -> strings.rebase.outcomeNothingToDo
    is InteractiveRebaseOutcome.Conflicted -> strings.rebase.outcomeConflicted(paths.joinToString())
    is InteractiveRebaseOutcome.StoppedForEdit -> strings.rebase.outcomeStoppedForEdit
}
