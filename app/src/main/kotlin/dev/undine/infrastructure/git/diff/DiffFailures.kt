package dev.undine.infrastructure.git.diff

import dev.undine.domain.UndineException
import kotlinx.coroutines.CancellationException

/**
 * diff 연산에서 올라온 실패를 도메인 예외로 번역한다.
 *
 * - `CancellationException` 은 삼키지 않고 그대로 올린다 — 삼키면 코루틴 취소가 동작하지 않는다.
 * - `IllegalArgumentException` 은 호출부 버그(사전조건 위반)이므로 사용자 메시지로 번역하지 않는다.
 * - 이미 도메인 예외인 것은 다시 감싸지 않는다.
 * - 그 밖의 실패만 `GitOperationFailed("diff.<op>", cause)` 로 감싼다 — 최후 수단이다.
 */
internal fun translateDiffFailure(operation: String, failure: Throwable): Nothing =
    when (failure) {
        is CancellationException, is UndineException, is IllegalArgumentException -> throw failure
        else -> throw UndineException.GitOperationFailed("diff.$operation", failure)
    }
