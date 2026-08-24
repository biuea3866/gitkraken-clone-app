package dev.undine.presentation.toolbar

import dev.undine.domain.PushResult
import dev.undine.domain.UndineException

/**
 * 끝난 원격 작업이 사용자에게 알릴 내용. **문구가 아니라 사실**만 담는다 —
 * 문구·톤 변환은 [remoteOperationMessage] 가 하고, 상태 홀더는 로케일을 알지 못한다.
 *
 * `sealed` 라 새 결과가 생기면 변환이 컴파일 시점에 빠짐을 알린다.
 */
sealed interface RemoteOperationOutcome {

    /** fetch 성공. 갱신 건수는 Gateway 가 돌려준 참조 목록의 크기다. */
    data class Fetched(val refCount: Int) : RemoteOperationOutcome

    /** pull 성공. 계약이 갱신 건수를 주지 않으므로 건수를 말하지 않는다. */
    data object Pulled : RemoteOperationOutcome

    /** push 수락. 단일 참조 대상이라 다중 참조 갱신 수를 주장하지 않는다. */
    data class Pushed(val force: Boolean) : RemoteOperationOutcome

    /** 원격이 push 를 거절했다. 전송 실패가 아니므로 실패로 취급하지 않는다. */
    data class PushRejected(val reason: PushResult.RejectReason) : RemoteOperationOutcome

    /**
     * 사용자가 취소해 명령이 결과를 남기지 못했다.
     *
     * 취소는 이미 전송된 작업을 **되돌리지 못한다** — JGit 호출은 중간에 끊기지 않아 원격 갱신이나
     * 병합이 끝난 뒤 취소가 감지될 수 있다. 그래서 안내 문구는 무엇을 확인해야 하는지까지 말하고,
     * force push 는 [forcePush] 로 갈라 덮어쓰기 가능성과 백업 참조 복구 경로를 알린다.
     */
    data class Cancelled(
        val operation: RemoteOperation,
        val forcePush: Boolean = false,
    ) : RemoteOperationOutcome

    /** 실패. 사용자가 취할 행동이 다르므로 [kind] 로 나눈다. */
    data class Failed(val operation: RemoteOperation, val kind: RemoteFailureKind) : RemoteOperationOutcome
}

/**
 * 실패 종류 — 안내 문구가 아니라 **사용자가 다음에 할 일**로 나눈다
 * (exception-handling 규칙 4).
 */
enum class RemoteFailureKind {
    /** 자격증명을 고쳐야 한다. */
    AUTHENTICATION,

    /** 원격 설정을 고쳐야 한다. */
    REMOTE_NOT_FOUND,

    /** 충돌을 해결해야 한다. */
    CONFLICT,

    /** 워킹트리를 정리해야 한다. */
    DIRTY_WORKING_TREE,

    /** 사용자가 고칠 수 없다 — 재시도와 로그 확인을 안내한다. */
    UNEXPECTED,
}

/**
 * 도메인 예외를 실패 종류로 옮긴다. 예외 메시지는 쓰지 않는다 —
 * 원격 URL·토큰이 실릴 수 있는 문자열을 화면 문구의 재료로 삼지 않기 위해서다.
 */
internal fun remoteFailureKindOf(failure: UndineException): RemoteFailureKind = when (failure) {
    is UndineException.AuthenticationFailed -> RemoteFailureKind.AUTHENTICATION
    is UndineException.NotFound -> when (failure.kind) {
        UndineException.NotFound.Kind.REMOTE -> RemoteFailureKind.REMOTE_NOT_FOUND
        else -> RemoteFailureKind.UNEXPECTED
    }
    is UndineException.Conflict -> RemoteFailureKind.CONFLICT
    is UndineException.DirtyWorkingTree -> RemoteFailureKind.DIRTY_WORKING_TREE
    else -> RemoteFailureKind.UNEXPECTED
}
