package dev.undine.domain

/**
 * push 결과. 전송 실패는 예외([UndineException])로 올리고, 여기서는 원격이 응답한
 * 수락·거절만 구분한다 — 거절을 성공으로 보고하지 않기 위함이다.
 */
sealed interface PushResult {

    data object Accepted : PushResult

    data class Rejected(val reason: RejectReason) : PushResult

    enum class RejectReason {
        NON_FAST_FORWARD,
        REMOTE_REJECTED,
    }
}
