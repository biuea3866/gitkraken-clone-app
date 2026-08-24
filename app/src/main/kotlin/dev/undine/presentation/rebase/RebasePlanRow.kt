package dev.undine.presentation.rebase

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebasePlanStep
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.rebase
import dev.undine.presentation.i18n.strings

private const val SINGLE_LINE = 1
private const val SHORT_HASH_LENGTH = 7

/** 계획 한 줄의 동작 지정·재정렬·메시지 편집을 한 묶음으로 받는다 (파라미터 수 제한). */
class RebaseRowEvents(
    val onMove: (from: Int, to: Int) -> Unit,
    val onAction: (index: Int, action: RebaseAction) -> Unit,
    val onReword: (index: Int, message: String) -> Unit,
)

/**
 * 계획의 한 줄 — 위에 해시·제목·원격 표시·이동 버튼, 아래에 동작 칩 여섯 개다.
 *
 * 한 행에 다 넣어 보고 되돌렸다: 칩 여섯 개가 폭을 다 먹어 `weight` 로 잡은 제목이 0 으로 접혀
 * **어느 커밋인지 읽을 수 없었다**. 동작 표시는 `git rebase -i` 키워드라 이미 가장 짧다.
 *
 * 재정렬은 **드래그와 버튼 둘 다**로 된다 — 드래그가 요구사항이지만 마우스 전용 경로만 두면
 * 키보드로 계획을 못 고친다 (`compose-ui` 규칙 8).
 */
@Composable
fun RebasePlanRow(
    index: Int,
    step: RebasePlanStep,
    stepCount: Int,
    events: RebaseRowEvents,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember(index) { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(UndineTokens.color.background)
            .onSizeChanged { size -> rowHeight = size.height.toFloat() }
            .pointerInput(index, stepCount) {
                detectDragGestures(
                    onDrag = { _, amount -> dragOffset += amount.y },
                    onDragEnd = {
                        val target = dropTargetIndex(index, dragOffset, rowHeight, stepCount)
                        dragOffset = 0f
                        if (target != index) events.onMove(index, target)
                    },
                    onDragCancel = { dragOffset = 0f },
                )
            }
            .testTag(RebaseTags.planRow(index)),
    ) {
        // 커밋 식별과 동작 칩을 두 줄로 나눈다 — 한 줄에 다 넣으면 칩 여섯 개가 폭을 다 먹어
        // weight 로 잡은 제목이 0 으로 접히고, 어느 커밋인지 읽을 수 없게 된다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.small, vertical = spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommitLabel(index = index, step = step)
            MoveButtons(index = index, stepCount = stepCount, onMove = events.onMove)
        }
        Row(
            modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.extraSmall),
        ) {
            ActionChoices(index = index, current = step.action, onAction = events.onAction)
        }
        if (step.action is RebaseAction.Reword) {
            RewordField(index = index, action = step.action, onReword = events.onReword)
        }
    }
}

/** 짧은 해시·제목·원격 표시. 제목이 남은 폭을 가져간다 (이동 버튼만 오른쪽에 고정). */
@Composable
private fun RowScope.CommitLabel(index: Int, step: RebasePlanStep) {
    val colors = UndineTokens.color
    val texts = strings.rebase

    BasicText(
        text = step.commit.id.value.take(SHORT_HASH_LENGTH),
        style = UndineTokens.typography.mono.copy(color = colors.foregroundTertiary),
    )
    BasicText(
        text = step.commit.message.lineSequence().firstOrNull().orEmpty(),
        modifier = Modifier.weight(1f).testTag(RebaseTags.rowMessage(index)),
        style = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        maxLines = SINGLE_LINE,
        overflow = TextOverflow.Ellipsis,
    )
    if (step.target.isPushed) {
        BasicText(
            text = texts.pushedMark,
            modifier = Modifier.testTag(RebaseTags.pushedMark(index)),
            style = UndineTokens.typography.caption.copy(color = colors.warning),
        )
    }
}

/**
 * 여섯 동작 중 하나. 지금 선택된 것은 누를 수 없게 두어 선택 상태가 드러난다.
 *
 * 표시는 `git rebase -i` 의 키워드 그대로다 — 도메인 개념은 영문을 유지하고(`output-style`),
 * 풀어 쓴 한국어는 칩 하나에 담기지 않아 목록을 밀어낸다.
 */
@Composable
private fun ActionChoices(index: Int, current: RebaseAction, onAction: (Int, RebaseAction) -> Unit) {
    val texts = strings.rebase
    val choices = listOf(
        RebaseActionChoice(ACTION_PICK, texts.actionPick, RebaseAction.Pick),
        RebaseActionChoice(ACTION_REWORD, texts.actionReword, RebaseAction.Reword("")),
        RebaseActionChoice(ACTION_EDIT, texts.actionEdit, RebaseAction.Edit),
        RebaseActionChoice(ACTION_SQUASH, texts.actionSquash, RebaseAction.Squash),
        RebaseActionChoice(ACTION_FIXUP, texts.actionFixup, RebaseAction.Fixup),
        RebaseActionChoice(ACTION_DROP, texts.actionDrop, RebaseAction.Drop),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        choices.forEach { choice ->
            UndineToolbarButton(
                label = choice.label,
                onClick = { onAction(index, choice.action) },
                enabled = !choice.matches(current),
                modifier = Modifier.testTag(RebaseTags.action(index, choice.key)),
            )
        }
    }
}

@Composable
private fun MoveButtons(index: Int, stepCount: Int, onMove: (Int, Int) -> Unit) {
    val texts = strings.rebase

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        UndineToolbarButton(
            label = texts.moveUp,
            onClick = { onMove(index, index - 1) },
            enabled = index > 0,
            modifier = Modifier.testTag(RebaseTags.moveUp(index)),
        )
        UndineToolbarButton(
            label = texts.moveDown,
            onClick = { onMove(index, index + 1) },
            enabled = index < stepCount - 1,
            modifier = Modifier.testTag(RebaseTags.moveDown(index)),
        )
    }
}

/** reword 로 지정한 줄의 새 메시지. 계획에 담기므로 실행 중 다시 묻지 않는다. */
@Composable
private fun RewordField(index: Int, action: RebaseAction.Reword, onReword: (Int, String) -> Unit) {
    val colors = UndineTokens.color

    BasicTextField(
        value = action.message,
        onValueChange = { message -> onReword(index, message) },
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = UndineTokens.spacing.medium,
                vertical = UndineTokens.spacing.extraSmall,
            )
            .testTag(RebaseTags.rewordField(index)),
    )
}

/** 동작 하나의 표시 이름·태그 키·지정할 값. */
private class RebaseActionChoice(
    val key: String,
    val label: String,
    val action: RebaseAction,
) {
    /**
     * 지금 지정된 동작인지. `Reword` 는 메시지가 달라도 같은 동작이라 타입으로만 본다 —
     * 값으로 비교하면 메시지를 한 글자 고칠 때마다 선택이 풀린다.
     */
    fun matches(current: RebaseAction): Boolean =
        if (action is RebaseAction.Reword) current is RebaseAction.Reword else current == action
}

internal const val ACTION_PICK = "pick"
internal const val ACTION_REWORD = "reword"
internal const val ACTION_EDIT = "edit"
internal const val ACTION_SQUASH = "squash"
internal const val ACTION_FIXUP = "fixup"
internal const val ACTION_DROP = "drop"
