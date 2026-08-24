package dev.undine.presentation.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.undine.domain.Progress
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineEmptyState
import dev.undine.presentation.design.component.UndineListRow
import dev.undine.presentation.design.component.UndineProgressBar
import dev.undine.presentation.design.component.UndineToast
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.welcome
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100

/**
 * 첫 실행 화면 — 최근 저장소 · 로컬 열기 · 원격 클론 세 경로를 한 화면에 둔다.
 *
 * **순수 컴포넌트**다: [state] 스냅샷과 [events] 콜백만 받고 UseCase·Gateway 를 알지 못한다
 * (compose-ui 규칙 1). 상태 소유자는 [WelcomeState] 이며 배선은 UND-26 이 한다.
 * clone 입력란의 글자도 예외가 아니다 — [WelcomeScreenState] 가 값을 들고 화면은 변경 이벤트만 올린다.
 */
@Composable
fun WelcomeScreen(
    state: WelcomeScreenState,
    events: WelcomeEvents,
    modifier: Modifier = Modifier,
) {
    val spacing = UndineTokens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UndineTokens.color.background)
            .padding(spacing.large)
            .testTag(WelcomeTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        BasicText(
            text = strings.welcome.title,
            style = UndineTokens.typography.title.copy(color = UndineTokens.color.foregroundPrimary),
        )
        state.notice?.let { notice -> WelcomeNoticeBar(notice = notice, onDismiss = events.onDismissNotice) }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            RecentRepositorySection(
                repositories = state.recentRepositories,
                events = events,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
            Column(
                modifier = Modifier.fillMaxHeight().weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                OpenLocalSection(onChooseDirectory = events.onChooseLocalDirectory)
                CloneSection(state = state, events = events.clone)
            }
        }
    }
}

/** 안내와 닫기 버튼. 닫기는 버튼이라 키보드로도 닫을 수 있다 (compose-ui 규칙 8). */
@Composable
private fun WelcomeNoticeBar(notice: WelcomeNotice, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(WelcomeTags.NOTICE),
        horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UndineToast(message = welcomeNoticeText(notice), tone = notice.tone())
        UndineToolbarButton(label = strings.common.close, onClick = onDismiss)
    }
}

/** 사라진 경로는 안내로 충분하고 되돌릴 수 없는 실패만 오류 색을 쓴다. */
private fun WelcomeNotice.tone(): UndineToastTone = when (this) {
    WelcomeNotice.TargetNotEmpty, is WelcomeNotice.CleanupFailed -> UndineToastTone.WARNING
    is WelcomeNotice.OpenFailed,
    WelcomeNotice.OpenFailedUnexpectedly,
    WelcomeNotice.AuthenticationFailed,
    WelcomeNotice.CloneFailed,
    -> UndineToastTone.ERROR
}

@Composable
private fun RecentRepositorySection(
    repositories: List<RecentRepository>,
    events: WelcomeEvents,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        SectionTitle(text = strings.welcome.recentTitle)
        if (repositories.isEmpty()) {
            UndineEmptyState(
                message = strings.welcome.recentEmpty,
                description = strings.welcome.recentEmptyDescription,
                modifier = Modifier.fillMaxWidth().testTag(WelcomeTags.RECENT_EMPTY),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag(WelcomeTags.RECENT_LIST)) {
                // key 는 경로다 — 목록 안에서 유일하고 순서가 바뀌어도 같은 항목을 가리킨다 (compose-ui 규칙 3).
                items(items = repositories, key = { it.path.value }) { repository ->
                    RecentRepositoryRow(repository = repository, events = events)
                }
            }
        }
    }
}

/**
 * 최근 목록의 한 행. 사라진 경로는 **비활성으로 표시만** 하고 목록에서 빼지 않는다 —
 * 빼는 것은 제거 버튼을 누른 사용자의 결정이다.
 */
@Composable
private fun RecentRepositoryRow(repository: RecentRepository, events: WelcomeEvents) {
    val available = repository.available
    val colors = UndineTokens.color

    UndineListRow(
        onClick = { if (available) events.onOpenRecent(repository.path) },
        modifier = Modifier
            .testTag(WelcomeTags.recentRow(repository.path))
            .semantics { if (!available) disabled() },
    ) {
        BasicText(
            text = repository.path.value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = UndineTokens.typography.body.copy(
                color = if (available) colors.foregroundPrimary else colors.foregroundTertiary,
            ),
        )
        if (!available) {
            BasicText(
                text = strings.welcome.recentUnavailable,
                style = UndineTokens.typography.caption.copy(color = colors.warning),
            )
        }
        UndineToolbarButton(
            label = strings.welcome.recentRemove,
            onClick = { events.onForgetRecent(repository.path) },
            modifier = Modifier.testTag(WelcomeTags.recentRemove(repository.path)),
        )
    }
}

@Composable
private fun OpenLocalSection(onChooseDirectory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        SectionTitle(text = strings.welcome.openTitle)
        UndineToolbarButton(
            label = strings.welcome.openAction,
            onClick = onChooseDirectory,
            modifier = Modifier.testTag(WelcomeTags.OPEN_LOCAL),
        )
    }
}

/**
 * 원격 클론. 진행 중에는 시작 버튼을 잠가 같은 대상에 두 clone 이 붙지 않게 한다 —
 * 한쪽의 정리가 다른 쪽이 받은 파일을 지운다.
 */
@Composable
private fun CloneSection(state: WelcomeScreenState, events: WelcomeCloneEvents) {
    val texts = strings.welcome
    val url = state.cloneUrl
    val target = state.cloneTarget

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small)) {
        SectionTitle(text = texts.cloneTitle)
        LabelledTextField(
            label = texts.cloneUrlLabel,
            value = url,
            onValueChange = events.onUrlChange,
            testTag = WelcomeTags.CLONE_URL,
        )
        LabelledTextField(
            label = texts.cloneTargetLabel,
            value = target,
            onValueChange = events.onTargetChange,
            testTag = WelcomeTags.CLONE_TARGET,
        )
        UndineToolbarButton(
            label = texts.cloneStart,
            onClick = { events.onStart(url, target) },
            modifier = Modifier.testTag(WelcomeTags.CLONE_START),
            enabled = !state.cloning && url.isNotBlank() && target.isNotBlank(),
        )
        if (state.cloning) {
            CloneProgress(progress = state.cloneProgress)
            UndineToolbarButton(
                label = texts.cloneCancel,
                onClick = events.onCancel,
                modifier = Modifier.testTag(WelcomeTags.CLONE_CANCEL),
            )
        }
    }
}

/** 진행량을 아직 모르는 구간(`null`)에서는 0% 로 꾸미지 않고 표시를 비운다. */
@Composable
private fun CloneProgress(progress: Progress?) {
    if (progress == null) return
    val percent = (progress.completedFraction * PERCENT_SCALE).roundToInt()

    Column(
        modifier = Modifier.fillMaxWidth().testTag(WelcomeTags.CLONE_PROGRESS),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall),
    ) {
        BasicText(
            text = strings.welcome.cloneProgress(progress.phase, percent),
            style = UndineTokens.typography.caption.copy(color = UndineTokens.color.foregroundSecondary),
        )
        UndineProgressBar(fraction = progress.completedFraction.toFloat())
    }
}

@Composable
private fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val fieldShape = RoundedCornerShape(shape.cornerSmall)

    Column(verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        BasicText(
            text = label,
            style = UndineTokens.typography.caption.copy(color = colors.foregroundSecondary),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
            cursorBrush = SolidColor(colors.foregroundPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .border(shape.borderThin, colors.border, fieldShape)
                .padding(horizontal = UndineTokens.spacing.small, vertical = UndineTokens.spacing.extraSmall)
                .testTag(testTag),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text = text,
        style = UndineTokens.typography.body.copy(color = UndineTokens.color.foregroundSecondary),
    )
}
