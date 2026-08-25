package dev.undine.domain.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.testsupport.commitId

internal val START_BRANCH: BisectStartPoint = BisectStartPoint.Branch(RefName("refs/heads/main"))

/**
 * 선형 이력을 흉내 내는 [BisectGateway] 대역.
 *
 * JGit 을 Mock 으로 대체한 것이 **아니라** domain interface 의 대역이다 — 실제 Git 동작은
 * `BisectGatewayImplSpec` 이 임시 저장소로 검증한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1·3).
 *
 * @param history 오래된 것부터 정렬된 선형 이력.
 * @param notLinear 값이 있으면 [surveyCandidates] 가 항상 이 미지원 결과를 돌려준다.
 */
internal class FakeBisectGateway(
    private val history: List<CommitId> = (1..8).map(::commitId),
    private val notLinear: CandidateSurvey.NotLinear? = null,
    private var session: BisectSession? = null,
) : BisectGateway {

    val checkedOut = mutableListOf<CommitId>()
    var cleared = false
        private set

    override suspend fun currentSession(): BisectSession? = session

    override suspend fun startPoint(): BisectStartPoint = START_BRANCH

    override suspend fun surveyCandidates(good: List<CommitId>, bad: CommitId): CandidateSurvey {
        val badIndex = history.indexOf(bad)
        val newestGoodIndex = good.map { commit -> history.indexOf(commit) }.filter { it >= 0 }.maxOrNull()
        return when {
            notLinear != null -> notLinear
            newestGoodIndex != null && newestGoodIndex >= badIndex -> CandidateSurvey.BadIsAncestorOfGood
            else -> CandidateSurvey.Linear(
                CandidateRange(history.subList((newestGoodIndex ?: -1) + 1, badIndex + 1)),
            )
        }
    }

    override suspend fun saveSession(expected: BisectSession?, session: BisectSession) {
        requireUnchanged(expected)
        this.session = session
    }

    override suspend fun beginProbe(expected: BisectSession, probe: CommitId) {
        requireUnchanged(expected)
        checkedOut += probe
        this.session = expected.copy(testing = probe)
    }

    override suspend fun clearSession() {
        // 구현과 같은 계약이다 — 진행 중이 아니면 거부한다. 대역이 조용히 성공하면 그 경로가 검증되지 않는다.
        session ?: throw UndineException.StateViolation("이분 탐색이 진행 중이 아닙니다")
        cleared = true
        session = null
    }

    /** 구현과 같은 계약이다 — 대조 없이 덮어쓰는 대역은 동시 전이 거절 경로를 검증하지 못한다. */
    private fun requireUnchanged(expected: BisectSession?) {
        if (session != expected) throw UndineException.StateViolation("이분 탐색 상태가 바뀌었습니다")
    }
}
