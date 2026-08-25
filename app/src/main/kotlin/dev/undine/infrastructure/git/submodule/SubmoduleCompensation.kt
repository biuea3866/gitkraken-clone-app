package dev.undine.infrastructure.git.submodule

import kotlinx.coroutines.CancellationException

/**
 * 정리·되돌리기 단계를 **끝까지 모두 시도**하고 실패를 모아 돌려준다.
 *
 * 서브모듈 정리는 `.gitmodules` · 저장소 설정 · 인덱스 · 워킹트리 · `.git/modules` 다섯 곳을 건드리고,
 * 그 단계들은 서로 독립이다. 첫 실패에서 멈추면 지운 곳과 남은 곳이 섞인 상태가 그대로 남아
 * 다음 clone 이 이상하게 동작한다 — 중단하는 쪽이 오히려 부분 적용을 키운다.
 *
 * 실패는 삼키지 않는다. 모아 둔 실패는 호출부가 원인에 붙여 그대로 올린다.
 * [CancellationException] 만은 모으지 않고 즉시 전파한다 — 삼키면 코루틴 취소가 동작하지 않는다.
 */
@Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
internal fun attemptAll(steps: List<() -> Unit>): List<Exception> = buildList {
    steps.forEach { step ->
        try {
            step()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            add(failure)
        }
    }
}

/**
 * [attemptAll] 로 전부 시도한 뒤 실패가 하나라도 있으면 첫 실패를 원인으로, 나머지를 suppressed 로
 * 붙여 한 번에 던진다 — 부분 실패를 성공으로 보고하지 않는다.
 */
internal fun completeAll(steps: List<() -> Unit>) {
    val failures = attemptAll(steps)
    val cause = failures.firstOrNull() ?: return
    failures.drop(1).forEach(cause::addSuppressed)
    throw cause
}
