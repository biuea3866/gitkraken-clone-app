package dev.undine.presentation.toolbar

import dev.undine.application.toolbar.FastForwardOutcome
import dev.undine.application.toolbar.FastForwardRefusal
import dev.undine.domain.PushResult
import dev.undine.presentation.design.component.UndineToastTone
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.ToolbarStrings
import dev.undine.presentation.i18n.toolbar

/** 사용자에게 보여줄 결과 한 줄. 톤은 색만 담당하고 상황 설명은 [text] 가 한다. */
data class RemoteOperationMessage(
    val text: String,
    val tone: UndineToastTone,
)

/**
 * 결과를 사용자 문구·톤으로 옮긴다.
 *
 * - non-fast-forward 거절은 **오류가 아니라 안내**다 — 사용자가 pull 로 해결할 수 있다.
 * - 실패 문구는 카탈로그 문장만 쓴다. 예외 메시지를 그대로 싣지 않으므로 원격 URL 의
 *   host·path·토큰이 화면에 나올 경로가 없다.
 */
fun remoteOperationMessage(strings: Strings, outcome: RemoteOperationOutcome): RemoteOperationMessage {
    val toolbar = strings.toolbar
    return when (outcome) {
        is RemoteOperationOutcome.Fetched ->
            RemoteOperationMessage(toolbar.fetched(outcome.refCount), UndineToastTone.NEUTRAL)

        RemoteOperationOutcome.Pulled ->
            RemoteOperationMessage(toolbar.pulled, UndineToastTone.NEUTRAL)

        is RemoteOperationOutcome.Pushed -> RemoteOperationMessage(
            text = if (outcome.force) toolbar.forcePushed else toolbar.pushed,
            tone = UndineToastTone.NEUTRAL,
        )

        is RemoteOperationOutcome.BranchPushed -> RemoteOperationMessage(
            text = if (outcome.force) {
                toolbar.branchForcePushed(outcome.branch.displayName())
            } else {
                toolbar.branchPushed(outcome.branch.displayName())
            },
            tone = UndineToastTone.NEUTRAL,
        )

        is RemoteOperationOutcome.BranchPulled -> branchPullMessage(toolbar, outcome.outcome)

        is RemoteOperationOutcome.PushRejected -> when (outcome.reason) {
            PushResult.RejectReason.NON_FAST_FORWARD ->
                RemoteOperationMessage(toolbar.nonFastForward, UndineToastTone.WARNING)
            PushResult.RejectReason.REMOTE_REJECTED ->
                RemoteOperationMessage(toolbar.remoteRejected, UndineToastTone.ERROR)
        }

        is RemoteOperationOutcome.Cancelled -> RemoteOperationMessage(
            text = cancelledText(toolbar, outcome),
            // fetch 는 원격 추적 참조만 건드려 확인할 것이 없다. 나머지는 부분 적용 가능성을 경고한다.
            tone = if (outcome.operation == RemoteOperation.FETCH) {
                UndineToastTone.NEUTRAL
            } else {
                UndineToastTone.WARNING
            },
        )

        is RemoteOperationOutcome.Failed ->
            RemoteOperationMessage(failureText(toolbar, outcome.kind), UndineToastTone.ERROR)
    }
}

/**
 * 지목 받기 결과 안내.
 *
 * **거부는 오류가 아니라 안내다** — 사용자가 체크아웃한 뒤 병합하면 해결할 수 있으므로 경고 톤으로
 * 내고, 무엇 때문에 멈췄는지까지 말한다. 대상 브랜치 이름을 문장에 담아 어느 행에서 시작했는지
 * 다시 기억하지 않게 한다.
 *
 * **기록만 실패한 이동을 중립 성공으로 접지 않는다** — ref 는 옮겨졌는데 실행 이력 항목이 없으면
 * 사용자는 되돌릴 수 있다고 믿는다. 그래프 조작이 같은 상황에 쓰는 문장·경고 톤을 그대로 붙여,
 * 적용됐다는 사실과 reflog 로 찾아야 한다는 사실을 함께 알린다 (결정 D16).
 */
private fun branchPullMessage(
    toolbar: ToolbarStrings,
    outcome: FastForwardOutcome,
): RemoteOperationMessage = when (outcome) {
    is FastForwardOutcome.FastForwarded -> if (outcome.undoRecordFailure == null) {
        RemoteOperationMessage(
            text = toolbar.branchFastForwarded(outcome.branch.displayName()),
            tone = UndineToastTone.NEUTRAL,
        )
    } else {
        RemoteOperationMessage(
            text = "${toolbar.branchFastForwarded(outcome.branch.displayName())} ${toolbar.undoRecordFailed}",
            tone = UndineToastTone.WARNING,
        )
    }

    is FastForwardOutcome.AlreadyUpToDate -> RemoteOperationMessage(
        text = toolbar.branchUpToDate(outcome.branch.displayName()),
        tone = UndineToastTone.NEUTRAL,
    )

    is FastForwardOutcome.Refused -> RemoteOperationMessage(
        text = when (outcome.reason) {
            FastForwardRefusal.NOT_FAST_FORWARD -> toolbar.branchNotFastForward(outcome.branch.displayName())
            FastForwardRefusal.NO_UPSTREAM -> toolbar.branchNoUpstream(outcome.branch.displayName())
            FastForwardRefusal.CURRENT_BRANCH -> toolbar.branchIsCurrent(outcome.branch.displayName())
        },
        tone = UndineToastTone.WARNING,
    )
}

/**
 * 취소 안내. 취소가 전송을 되돌리지는 못하므로 **작업마다 확인할 대상**을 알린다 —
 * force push 는 원격 이력이 이미 덮어써졌을 수 있어 백업 참조로 되돌리는 경로까지 말한다.
 */
private fun cancelledText(toolbar: ToolbarStrings, cancelled: RemoteOperationOutcome.Cancelled): String =
    if (cancelled.forcePush) {
        toolbar.cancelledForcePush
    } else {
        when (cancelled.operation) {
            RemoteOperation.FETCH -> toolbar.cancelledFetch
            RemoteOperation.PULL -> toolbar.cancelledPull
            RemoteOperation.PUSH -> toolbar.cancelledPush
        }
    }

/** 실패 종류별 안내 문장. 종류마다 사용자가 취할 행동이 달라 문구를 공유하지 않는다. */
private fun failureText(toolbar: ToolbarStrings, kind: RemoteFailureKind): String = when (kind) {
    RemoteFailureKind.AUTHENTICATION -> toolbar.authenticationFailed
    RemoteFailureKind.REMOTE_NOT_FOUND -> toolbar.remoteNotFound
    RemoteFailureKind.CONFLICT -> toolbar.conflict
    RemoteFailureKind.DIRTY_WORKING_TREE -> toolbar.dirtyWorkingTree
    RemoteFailureKind.UNEXPECTED -> toolbar.unexpectedFailure
}
