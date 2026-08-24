package dev.undine.presentation.commitdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.commitDetail
import dev.undine.presentation.i18n.strings
import java.time.ZoneId

/** 링크에 보이는 부모 해시 길이. 전체 해시는 이 커밋의 해시 자리에만 둔다. */
private const val SHORT_HASH_LENGTH = 7

/**
 * 커밋 한 건의 메타 — 전체 해시(복사 가능) · 작성자 · 커미터 · 각각의 시각 · 부모 링크 ·
 * 메시지 · 병합 커밋의 기준 부모 선택.
 *
 * 작성자와 커미터가 다르면(cherry-pick·rebase·amend 결과) 둘 다 그린다. 같으면 커미터 줄을 빼
 * 같은 이름을 두 번 읽게 하지 않는다.
 */
@Composable
internal fun CommitMetaSection(
    commit: Commit,
    state: CommitDetailState,
    modifier: Modifier = Modifier,
    onSelectParentCommit: (CommitId) -> Unit = {},
) {
    val allStrings = strings
    val texts = allStrings.commitDetail
    val spacing = UndineTokens.spacing
    // 표시 시간대는 사용자의 시스템 시간대다 — 시간대를 고르는 설정은 아직 없다.
    val zone = remember { ZoneId.systemDefault() }

    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        CommitHashRow(commitId = commit.id)
        LabeledValue(
            label = texts.author,
            value = texts.person(commit.author.name, commit.author.email),
            testTag = CommitDetailTags.AUTHOR,
        )
        LabeledValue(
            label = texts.authoredAt,
            value = formatCommitTimestamp(allStrings, commit.authoredAt, zone),
            testTag = CommitDetailTags.AUTHORED_AT,
        )
        if (commit.committer != commit.author) {
            LabeledValue(
                label = texts.committer,
                value = texts.person(commit.committer.name, commit.committer.email),
                testTag = CommitDetailTags.COMMITTER,
            )
        }
        LabeledValue(
            label = texts.committedAt,
            value = formatCommitTimestamp(allStrings, commit.committedAt, zone),
            testTag = CommitDetailTags.COMMITTED_AT,
        )
        ParentsRow(commit = commit, onSelectParentCommit = onSelectParentCommit)
        CommitMessageSection(commit = commit, state = state)
        if (commit.parents.size > 1) {
            BaseParentSelector(commit = commit, state = state)
        }
    }
}

/**
 * 전체 해시. 클릭 또는 포커스 후 Enter 로 클립보드에 복사한다 — 마우스 전용 동작을 만들지 않는다
 * (compose-ui 규칙 8). 짧은 해시가 아니라 전체를 복사한다: 붙여넣는 쪽이 잘라 쓰는 편이 안전하다.
 */
@Composable
private fun CommitHashRow(commitId: CommitId) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing
    val clipboard = LocalClipboardManager.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = texts.hash,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = commitId.value,
            style = typography.mono.copy(color = colors.foregroundPrimary),
            modifier = Modifier
                .clip(RoundedCornerShape(UndineTokens.shape.cornerSmall))
                .clickable(onClickLabel = texts.copyHash) {
                    clipboard.setText(AnnotatedString(commitId.value))
                }
                .padding(horizontal = spacing.extraSmall)
                .testTag(CommitDetailTags.HASH),
        )
    }
}

/** 라벨 + 값 한 줄. 값 쪽에만 태그를 달아 테스트가 값 노드를 바로 집는다. */
@Composable
private fun LabeledValue(label: String, value: String, testTag: String) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = value,
            style = typography.body.copy(color = colors.foregroundPrimary),
            modifier = Modifier.testTag(testTag),
        )
    }
}

/**
 * 부모 커밋 링크. 부모가 없는 최초 커밋은 그 사실을 글로 알린다 — 빈 자리를 두면
 * 값을 못 읽은 것인지 부모가 없는 것인지 구분되지 않는다.
 */
@Composable
private fun ParentsRow(commit: Commit, onSelectParentCommit: (CommitId) -> Unit) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing

    if (commit.parents.isEmpty()) {
        BasicText(
            text = texts.noParents,
            style = typography.body.copy(color = colors.foregroundSecondary),
            modifier = Modifier.testTag(CommitDetailTags.PARENTS),
        )
        return
    }

    Row(
        modifier = Modifier.testTag(CommitDetailTags.PARENTS),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = texts.parents,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        commit.parents.forEachIndexed { index, parent ->
            BasicText(
                text = parent.value.take(SHORT_HASH_LENGTH),
                style = typography.mono.copy(color = colors.accent),
                modifier = Modifier
                    .clickable { onSelectParentCommit(parent) }
                    .testTag(CommitDetailTags.parentLink(index)),
            )
        }
    }
}

/** 제목은 항상 보이고 본문은 접혀 있다 — 긴 메시지가 파일 목록을 화면 밖으로 밀지 않게 한다. */
@Composable
private fun CommitMessageSection(commit: Commit, state: CommitDetailState) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing
    val parts = remember(commit.message) { CommitMessageParts.of(commit.message) }
    val expanded = state.isMessageExpanded(commit)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        BasicText(
            text = parts.subject,
            style = typography.title.copy(color = colors.foregroundPrimary),
            modifier = Modifier.testTag(CommitDetailTags.MESSAGE_SUBJECT),
        )
        if (parts.hasBody) {
            BasicText(
                text = if (expanded) texts.collapseMessage else texts.expandMessage,
                style = typography.caption.copy(color = colors.accent),
                modifier = Modifier
                    .clickable { state.toggleMessage(commit) }
                    .testTag(CommitDetailTags.MESSAGE_TOGGLE),
            )
            if (expanded) {
                BasicText(
                    text = parts.body,
                    style = typography.body.copy(color = colors.foregroundSecondary),
                    modifier = Modifier.testTag(CommitDetailTags.MESSAGE_BODY),
                )
            }
        }
    }
}

/**
 * 병합 커밋의 기준 부모 선택. 기본은 첫 부모지만, 병합으로 들어온 변경을 보려면
 * 두 번째 부모 기준이 필요하다. 부모가 하나뿐이면 고를 것이 없어 그리지 않는다.
 */
@Composable
private fun BaseParentSelector(commit: Commit, state: CommitDetailState) {
    val texts = strings.commitDetail
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    val spacing = UndineTokens.spacing
    val selectedIndex = state.baseParentIndexOf(commit)

    Row(
        modifier = Modifier.testTag(CommitDetailTags.BASE_PARENT_SELECTOR),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = texts.baseParent,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        commit.parents.forEachIndexed { index, _ ->
            val selected = index == selectedIndex
            BasicText(
                text = texts.parentOption(index + 1),
                style = typography.caption.copy(
                    color = if (selected) colors.accent else colors.foregroundSecondary,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(UndineTokens.shape.cornerSmall))
                    .clickable { state.selectBaseParent(commit, index) }
                    .padding(horizontal = spacing.extraSmall)
                    .testTag(CommitDetailTags.baseParentOption(index)),
            )
        }
    }
}
