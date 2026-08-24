package dev.undine.presentation.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.undine.domain.Branch
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.sidebar
import dev.undine.presentation.i18n.strings

/**
 * 삭제 확인 대화상자.
 *
 * 두 단계 모두 같은 대화상자를 쓰고 **문구만 바뀐다** — 미병합 단계에서는 "정말 삭제할까요" 가 아니라
 * 결과(커밋이 도달 불가가 된다)를 문장으로 알린다. 확인 버튼은 [SidebarState.confirmDelete] 하나이며,
 * 취소는 어떤 삭제도 실행하지 않는다.
 */
@Composable
internal fun SidebarConfirmationPanel(
    confirmation: SidebarConfirmation,
    state: SidebarState,
    modifier: Modifier = Modifier,
) {
    val sidebarStrings = strings.sidebar
    val title: String
    val message: String
    when (confirmation) {
        is SidebarConfirmation.DeleteBranch -> {
            title = sidebarStrings.deleteTitle
            message = sidebarStrings.deleteMessage(confirmation.branch.name.value)
        }

        is SidebarConfirmation.ForceDeleteUnmerged -> {
            title = sidebarStrings.unmergedTitle
            message = sidebarStrings.unmergedMessage(confirmation.branch.name.value)
        }
    }

    SidebarPanel(title = title, modifier = modifier.testTag(SidebarTags.CONFIRM_DIALOG)) {
        BasicText(
            text = message,
            style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
        )
        SidebarPanelActions(
            onCancel = state::dismiss,
            onAccept = state::confirmDelete,
            cancelTag = SidebarTags.CONFIRM_CANCEL,
            acceptTag = SidebarTags.CONFIRM_ACCEPT,
            acceptEnabled = !state.deleteInProgress,
        )
    }
}

/**
 * 이름 변경 대화상자.
 *
 * 입력 초안은 이 대화상자의 편집 상태라 여기서 기억한다 — 커서를 현재 이름 끝에 두어 뒤에 이어
 * 적을 수 있게 한다. 확정된 새 이름만 [SidebarState.submitRename] 으로 올라간다.
 */
@Composable
internal fun SidebarRenamePanel(
    branch: Branch,
    state: SidebarState,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val sidebarStrings = strings.sidebar
    var draft by remember(branch) {
        mutableStateOf(
            TextFieldValue(text = branch.name.value, selection = TextRange(branch.name.value.length)),
        )
    }
    val fieldLabel = sidebarStrings.renameField

    SidebarPanel(title = sidebarStrings.renameTitle, modifier = modifier.testTag(SidebarTags.RENAME_DIALOG)) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .border(UndineTokens.shape.borderThin, colors.border)
                .padding(UndineTokens.spacing.small)
                .semantics { contentDescription = fieldLabel }
                .testTag(SidebarTags.RENAME_FIELD),
            textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.foregroundPrimary),
            singleLine = true,
        )
        SidebarPanelActions(
            onCancel = state::dismiss,
            onAccept = { state.submitRename(draft.text) },
            cancelTag = SidebarTags.RENAME_CANCEL,
            acceptTag = SidebarTags.RENAME_ACCEPT,
        )
    }
}

@Composable
private fun SidebarPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .border(UndineTokens.shape.borderThick, colors.border)
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        BasicText(
            text = title,
            style = UndineTokens.typography.title.copy(color = colors.foregroundPrimary),
        )
        content()
    }
}

/**
 * 취소가 앞, 확인이 뒤다 — 파괴적 동작 앞에서 기본 포커스가 취소에 먼저 닿게 한다.
 *
 * [acceptEnabled] 가 `false` 면 확인만 막고 취소는 남긴다 — 진행 중인 요청 때문에 사용자가
 * 대화상자에 갇히지 않게 한다.
 */
@Composable
private fun SidebarPanelActions(
    onCancel: () -> Unit,
    onAccept: () -> Unit,
    cancelTag: String,
    acceptTag: String,
    acceptEnabled: Boolean = true,
) {
    val commonStrings = strings.common

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        UndineToolbarButton(
            label = commonStrings.cancel,
            onClick = onCancel,
            modifier = Modifier.testTag(cancelTag),
        )
        UndineToolbarButton(
            label = commonStrings.ok,
            onClick = onAccept,
            modifier = Modifier.testTag(acceptTag),
            enabled = acceptEnabled,
        )
    }
}
