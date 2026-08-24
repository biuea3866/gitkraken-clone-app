package dev.undine.presentation.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.RefName
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.design.component.UndineToast
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.toolbar

/** 로컬 브랜치 참조의 사용자 표기 접두사. 경고 문장에 `refs/heads/` 를 그대로 보여주지 않는다. */
private const val LOCAL_BRANCH_PREFIX = "refs/heads/"

/**
 * 원격 작업 툴바 — fetch·pull·push 시작, 진행·취소, 결과 안내, force push 확인, ahead/behind 배지.
 *
 * **상태는 [state] 가 소유한다** (compose-ui 규칙 1). 이 컴포넌트가 `remember` 로 들고 있는 것은
 * 메뉴 열림·확인 대기처럼 **화면에만 존재하는 임시 표시 상태**뿐이며, 원격 작업의 진행·결과는
 * 하나도 여기에 두지 않는다 — 그래야 리컴포지션이 진행 중인 작업을 잃지 않는다.
 *
 * force push 는 기본 버튼에 없다. 더 보기 메뉴 → 문장 경고 → 명시적 확인을 지나야
 * `push(force = true)` 가 나간다. 백업 ref 와 force-with-lease 는 Gateway 의 책임이라
 * 여기서 중복 구현하지 않는다.
 */
@Composable
fun RemoteToolbar(
    state: RemoteToolbarState,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing
    var menuExpanded by remember { mutableStateOf(false) }
    var forcePushPending by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(UndineTokens.color.background)
            .padding(spacing.small)
            .testTag(ToolbarTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        RemoteActionRow(
            state = state,
            onToggleMenu = { menuExpanded = !menuExpanded },
        )
        if (state.runningOperation != null) {
            UndineProgressBar(
                fraction = state.progressFraction,
                modifier = Modifier.testTag(ToolbarTags.PROGRESS),
            )
        }
        if (menuExpanded && state.isEnabled(RemoteOperation.PUSH)) {
            UndineToolbarButton(
                label = strings.toolbar.forcePush,
                onClick = {
                    menuExpanded = false
                    forcePushPending = true
                },
                modifier = Modifier.testTag(ToolbarTags.FORCE_PUSH),
            )
        }
        ForcePushConfirmation(
            visible = forcePushPending,
            branch = state.branch?.name,
            remote = state.pushTargetRemote,
            onConfirm = {
                forcePushPending = false
                state.push(force = true)
            },
            onDismiss = { forcePushPending = false },
        )
        state.notice?.let { RemoteToolbarNoticeText(it) }
        state.outcome?.let { outcome ->
            val message = remoteOperationMessage(strings, outcome)
            UndineToast(
                message = message.text,
                // 안내를 읽은 뒤 누르면 닫힌다 — 다음 작업 시작도 안내를 지운다.
                modifier = Modifier
                    .clickable(onClick = state::dismissOutcome)
                    .testTag(ToolbarTags.MESSAGE)
                    .semantics(mergeDescendants = true) { },
                tone = message.tone,
            )
        }
    }
}

/**
 * 버튼 한 줄. 진행 중에는 단계명과 취소가 함께 뜬다 — 진행 표시만 두고 빠져나갈 길을 막지 않는다.
 *
 * 실행 중에는 원격 버튼 전체가 비활성이다. 저장소 핸들이 스레드 안전하지 않아 동시 실행이
 * 손상을 부르므로, "같은 작업 버튼 비활성" 보다 넓게 잠근다.
 */
@Composable
private fun RemoteActionRow(
    state: RemoteToolbarState,
    onToggleMenu: () -> Unit,
) {
    val toolbar = strings.toolbar

    Row(
        horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UndineToolbarButton(
            label = toolbar.fetch,
            onClick = state::fetch,
            modifier = Modifier.testTag(ToolbarTags.FETCH),
            enabled = state.isEnabled(RemoteOperation.FETCH),
        )
        UndineToolbarButton(
            label = toolbar.pull,
            onClick = state::pull,
            modifier = Modifier.testTag(ToolbarTags.PULL),
            enabled = state.isEnabled(RemoteOperation.PULL),
        )
        UndineToolbarButton(
            label = toolbar.push,
            onClick = { state.push() },
            modifier = Modifier.testTag(ToolbarTags.PUSH),
            enabled = state.isEnabled(RemoteOperation.PUSH),
        )
        if (state.branch != null) {
            BasicText(
                text = toolbar.aheadBehind(state.ahead, state.behind),
                modifier = Modifier.testTag(ToolbarTags.AHEAD_BEHIND),
                style = UndineTokens.typography.caption.copy(
                    color = UndineTokens.color.foregroundSecondary,
                ),
            )
        }
        UndineToolbarButton(
            label = toolbar.moreActions,
            onClick = onToggleMenu,
            modifier = Modifier.testTag(ToolbarTags.MORE_ACTIONS),
            enabled = state.isEnabled(RemoteOperation.PUSH),
        )
        if (state.runningOperation != null) {
            RunningIndicator(state)
        }
    }
}

/**
 * 진행 중 표시 — 단계명과 취소 경로.
 *
 * 취소를 요청한 뒤에는 취소 버튼 대신 **취소 중** 을 남긴다. 명령은 아직 저장소를 잡고 있고
 * 결과도 정해지지 않았으므로, 버튼을 그대로 두면 같은 요청을 다시 누르게 된다.
 */
@Composable
private fun RunningIndicator(state: RemoteToolbarState) {
    val caption = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary)

    BasicText(
        text = state.phase,
        modifier = Modifier.testTag(ToolbarTags.PHASE),
        style = caption,
    )
    if (state.cancelRequested) {
        BasicText(
            text = strings.toolbar.cancelling,
            modifier = Modifier.testTag(ToolbarTags.CANCELLING),
            style = caption,
        )
    } else {
        UndineToolbarButton(
            label = strings.common.cancel,
            onClick = state::cancel,
            modifier = Modifier.testTag(ToolbarTags.CANCEL),
        )
    }
}

/**
 * force push 확인. 무엇이 덮어써지는지 **문장으로** 알리고 확인을 받는다.
 *
 * [branch]·[remote] 중 하나라도 없으면 올릴 대상을 문장에 적을 수 없으므로 그리지 않는다 —
 * 대상을 못 적는 경고는 확인의 근거가 되지 못한다.
 */
@Composable
private fun ForcePushConfirmation(
    visible: Boolean,
    branch: RefName?,
    remote: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible || branch == null || remote == null) return

    val spacing = UndineTokens.spacing
    val shape = UndineTokens.shape
    val panelShape = RoundedCornerShape(shape.cornerMedium)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(UndineTokens.color.surface)
            .border(shape.borderThick, UndineTokens.color.warning, panelShape)
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        BasicText(
            text = strings.toolbar.forcePushWarning(branch = branch.displayName(), remote = remote),
            modifier = Modifier.testTag(ToolbarTags.FORCE_PUSH_WARNING),
            style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundPrimary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = strings.toolbar.forcePushConfirm,
                onClick = onConfirm,
                modifier = Modifier.testTag(ToolbarTags.FORCE_PUSH_CONFIRM),
            )
            UndineToolbarButton(
                label = strings.common.cancel,
                onClick = onDismiss,
                modifier = Modifier.testTag(ToolbarTags.FORCE_PUSH_DISMISS),
            )
        }
    }
}

/** 비활성 사유. 비활성 버튼만 두고 왜 못 누르는지 숨기지 않는다. */
@Composable
private fun RemoteToolbarNoticeText(notice: RemoteToolbarNotice) {
    val toolbar = strings.toolbar
    BasicText(
        text = when (notice) {
            RemoteToolbarNotice.NO_REMOTE -> toolbar.noRemote
            RemoteToolbarNotice.DETACHED_HEAD -> toolbar.detachedHead
            RemoteToolbarNotice.NO_UPSTREAM -> toolbar.noUpstream
        },
        modifier = Modifier.testTag(ToolbarTags.NOTICE),
        style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary),
    )
}

/** 사용자에게 보여줄 브랜치 이름. `refs/heads/main` 을 `main` 으로 줄인다. */
private fun RefName.displayName(): String = value.removePrefix(LOCAL_BRANCH_PREFIX)
