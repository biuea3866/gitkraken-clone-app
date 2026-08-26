package dev.undine.presentation.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.RecoveryStrings

@Composable
internal fun BisectSection(
    state: RecoveryState,
    copy: RecoveryStrings,
    modifier: Modifier = Modifier,
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        BasicText(copy.bisect, style = typography.title.copy(color = colors.foregroundPrimary))
        val session = state.bisectSession
        BisectStartRow(state, copy)
        state.bisectSummary?.let { summary -> BisectSummaryText(summary, copy) }
        state.bisectResult?.let { result -> BisectResultText(result, copy) }
        BisectHistoryText(state.bisectHistory, copy)
        BasicText(
            copy.historyNotChronological,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            ActionButton(copy.markGood) { state.markBisect(BisectVerdict.GOOD) }
            ActionButton(copy.markBad) { state.markBisect(BisectVerdict.BAD) }
            ActionButton(copy.skip) { state.markBisect(BisectVerdict.SKIP) }
            if (state.resetVisible) {
                ActionButton(copy.reset, enabled = session != null) { state.resetBisect() }
            }
        }
        if (state.bisectFailure != null) {
            BasicText(copy.loadFailed, style = typography.caption.copy(color = colors.deletion))
        }
        // 세션은 바뀌었는데 Undo 항목만 없어진 경우다. 판정 실패와 다른 문구로 구분해 알린다.
        if (state.bisectUndoRecordFailure != null) {
            BasicText(copy.undoRecordFailed, style = typography.caption.copy(color = colors.warning))
        }
    }
}

/**
 * good·bad 경계 지정과 탐색 시작.
 *
 * 경계는 위 reflog 목록에서 고른 커밋으로 정한다 — 화면이 이미 보여 주는 값이라 사용자가 해시를
 * 옮겨 적을 필요가 없다.
 */
@Composable
private fun BisectStartRow(state: RecoveryState, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography
    val selected = state.selectedEntry
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
        ActionButton(copy.bisectPickGood, enabled = selected != null) {
            selected?.let { state.selectBisectGood(it.to) }
        }
        ActionButton(copy.bisectPickBad, enabled = selected != null) {
            selected?.let { state.selectBisectBad(it.to) }
        }
        ActionButton(copy.bisectStart, enabled = state.canStartBisect) { state.startSelectedBisect() }
    }
    state.bisectGood?.let { good ->
        BasicText(
            copy.bisectBoundaryGood(good.value.take(SHORT_HASH_LENGTH)),
            style = typography.mono.copy(color = colors.addition),
        )
    }
    state.bisectBad?.let { bad ->
        BasicText(
            copy.bisectBoundaryBad(bad.value.take(SHORT_HASH_LENGTH)),
            style = typography.mono.copy(color = colors.deletion),
        )
    }
    if (!state.canStartBisect) {
        BasicText(copy.bisectBoundaryMissing, style = typography.caption.copy(color = colors.foregroundTertiary))
    }
}

/**
 * 현재 검사 대상과 남은 후보·예상 검사 횟수.
 *
 * 복원한 세션은 후보 수를 보유하지 않으므로 추정값을 지어내지 않고 그 사실을 알린다.
 */
@Composable
private fun BisectSummaryText(summary: BisectSummaryDisplay, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    summary.target?.let { target ->
        BasicText(
            copy.currentTarget(target.value.take(SHORT_HASH_LENGTH)),
            style = typography.mono.copy(color = colors.accent),
        )
    }
    val candidates = summary.remainingCandidates
    val checks = summary.expectedRemainingChecks
    if (candidates == null || checks == null) {
        BasicText(copy.summaryUnknownCounts, style = typography.caption.copy(color = colors.foregroundTertiary))
    } else {
        BasicText(
            copy.remainingCandidates(candidates),
            style = typography.body.copy(color = colors.foregroundSecondary),
        )
        BasicText(copy.remainingChecks(checks), style = typography.body.copy(color = colors.foregroundSecondary))
    }
}

@Composable
private fun BisectHistoryText(history: BisectHistoryDisplay, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    if (history.good.isNotEmpty()) {
        BasicText(
            copy.historyGood(history.good.joinToString { it.value.take(SHORT_HASH_LENGTH) }),
            style = typography.mono.copy(color = colors.addition),
        )
    }
    history.currentBad?.let { bad ->
        BasicText(
            copy.historyCurrentBad(bad.value.take(SHORT_HASH_LENGTH)),
            style = typography.mono.copy(color = colors.deletion),
        )
    }
    if (history.skipped.isNotEmpty()) {
        BasicText(
            copy.historySkipped(history.skipped.joinToString { it.value.take(SHORT_HASH_LENGTH) }),
            style = typography.mono.copy(color = colors.warning),
        )
    }
}

@Composable
private fun BisectResultText(result: BisectResult, copy: RecoveryStrings) {
    val colors = UndineTokens.color
    val typography = UndineTokens.typography
    when (result) {
        // 현재 대상·후보 수·예상 횟수는 BisectSummaryText 가 이미 보여 준다 — 두 번 쓰지 않는다.
        is BisectResult.Testing -> Unit

        is BisectResult.FirstBad -> BasicText(
            copy.firstBad(result.commit.value.take(SHORT_HASH_LENGTH)),
            style = typography.body.copy(color = colors.addition),
        )
        is BisectResult.Inconclusive -> Column {
            BasicText(copy.inconclusive, style = typography.body.copy(color = colors.warning))
            BasicText(
                copy.inconclusiveReason,
                style = typography.caption.copy(color = colors.foregroundSecondary),
            )
            result.candidates.forEach { candidate ->
                BasicText(
                    candidate.value.take(SHORT_HASH_LENGTH),
                    style = typography.mono.copy(color = colors.foregroundSecondary),
                )
            }
        }

        is BisectResult.ReversedRange, is BisectResult.Unsupported ->
            BasicText(copy.loadFailed, style = typography.body.copy(color = colors.warning))
    }
}
