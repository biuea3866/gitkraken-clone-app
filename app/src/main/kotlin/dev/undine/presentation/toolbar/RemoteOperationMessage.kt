package dev.undine.presentation.toolbar

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
