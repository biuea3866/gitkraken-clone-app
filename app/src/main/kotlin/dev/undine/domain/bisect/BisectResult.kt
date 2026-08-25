package dev.undine.domain.bisect

import dev.undine.domain.CommitId

/**
 * 이분 탐색 한 걸음의 결과.
 *
 * **예상 가능한 판정·경고·미지원은 전부 여기에 있다.** 예외로 올리면 화면이 다음에 무엇을 할 수
 * 있는지 알려줄 수 없다 — 후보를 좁히지 못한 것도, 계산하지 못하는 이력도 사용자가 skip 하거나
 * reset 하면 되는 상황이지 사고가 아니다.
 */
sealed interface BisectResult {

    /**
     * 다음 검사 대상을 체크아웃했다. 사용자가 [commit] 을 확인하고 good/bad/skip 을 판정한다.
     *
     * @param remainingCandidates 아직 최초 나쁜 커밋일 수 있는 커밋 수.
     * @param expectedRemainingChecks "앞으로 약 N 번" 을 보여주기 위한 추정치.
     */
    data class Testing(
        val commit: CommitId,
        val remainingCandidates: Int,
        val expectedRemainingChecks: Int,
    ) : BisectResult

    /** 최초 나쁜 커밋을 하나로 확정했다. */
    data class FirstBad(val commit: CommitId) : BisectResult

    /**
     * skip 때문에 하나로 좁히지 못했다. [candidates] 중 어느 것이든 최초 나쁜 커밋일 수 있다.
     *
     * **하나로 단정하지 않는다** — 건너뛴 커밋을 후보에서 빼고 답을 내면 틀린 커밋을 지목하게 된다.
     */
    data class Inconclusive(val candidates: List<CommitId>) : BisectResult

    /**
     * bad 가 good 의 조상이다. good/bad 를 반대로 지정한 흔한 실수라 경고로 알리고 **세션을
     * 시작하지 않는다** — 좁힐 구간이 없어 시작해도 할 일이 없다.
     */
    data class ReversedRange(val good: CommitId, val bad: CommitId) : BisectResult

    /**
     * 1차 구현이 계산하지 않는 이력이다 (선형 이력 전용).
     *
     * **세션은 살아 있다** — 실패 상태로 만들면 사용자가 skip 도 reset 도 하지 못한다.
     */
    data class Unsupported(val reason: BisectUnsupportedReason, val at: CommitId?) : BisectResult
}
