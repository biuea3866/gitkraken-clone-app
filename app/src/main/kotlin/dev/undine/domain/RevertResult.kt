package dev.undine.domain

/** revert 결과. 충돌은 실패가 아니라 사용자가 해결해야 하는 상태이므로 결과 타입으로 구분한다. */
sealed interface RevertResult {

    data class Succeeded(val commit: CommitId) : RevertResult

    data class Conflicted(val paths: List<String>) : RevertResult
}
