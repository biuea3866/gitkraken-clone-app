package dev.undine.domain.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException

private const val ALREADY_IN_PROGRESS = "이분 탐색이 이미 진행 중입니다"
private const val NOT_IN_PROGRESS = "이분 탐색이 진행 중이 아닙니다"
private const val NOTHING_TO_MARK = "판정할 검사 대상이 체크아웃돼 있지 않습니다"

/**
 * 이분 탐색의 규칙을 갖는 도메인 서비스. 저장소 접근은 [BisectGateway] 가 한다.
 *
 * 규칙은 네 가지다.
 * - **다음 대상은 후보 구간의 가운데다.** 계산 자체는 [CandidateRange] 가 순수 함수로 갖는다.
 * - **좁히지 못한 것을 확정으로 만들지 않는다.** skip 으로 검사할 대상이 사라졌는데 후보가 둘 이상
 *   남아 있으면 [BisectResult.Inconclusive] 다 — 하나로 단정하면 틀린 커밋을 지목한다.
 * - **계산하지 못하는 이력은 실패가 아니라 미지원이다.** 세션을 살려 두고 skip 도 받아야 사용자가
 *   그 자리에서 빠져나올 수 있다.
 * - **판정은 HEAD 를 움직이기 전에 기록한다.** 시작 지점과 판정이 먼저 저장소에 남아 있어야 중간에
 *   취소·실패가 끼어도 돌아갈 자리와 이어갈 근거가 남는다.
 */
class BisectService(private val bisectGateway: BisectGateway) {

    /** 저장소에 남아 있는 진행 중 세션. 앱을 다시 켜도 이 값으로 이어서 진행한다. */
    suspend fun currentSession(): BisectSession? = bisectGateway.currentSession()

    /**
     * [good] 과 [bad] 로 탐색을 시작하고 첫 검사 대상을 체크아웃한다.
     *
     * 세션은 체크아웃 **전에** 기록한다 — HEAD 를 먼저 옮기면 실패했을 때 돌아갈 자리를 잃는다.
     *
     * 아래의 "진행 중이 아님" 확인은 **친절한 조기 거절일 뿐 잠금이 아니다.** 실제 보장은 기록 직전
     * 저장소에서 다시 대조하는 [BisectGateway.saveSession] 의 `expected` 가 한다 — 확인과 기록
     * 사이에 다른 시작이 끼어들면 그 자리에서 거절된다.
     *
     * @throws UndineException.StateViolation 이미 진행 중인 세션이 있을 때
     * @throws UndineException.NotFound 지정한 커밋이 저장소에 없을 때
     */
    suspend fun start(good: CommitId, bad: CommitId): BisectResult {
        if (bisectGateway.currentSession() != null) {
            throw UndineException.StateViolation(ALREADY_IN_PROGRESS)
        }
        // 저장소를 건드리기 전에 방향부터 본다 — 반대로 지정한 세션을 열어 두면 사용자가 되돌릴 일만 는다.
        val survey = bisectGateway.surveyCandidates(listOf(good), bad)
        if (survey is CandidateSurvey.BadIsAncestorOfGood) return BisectResult.ReversedRange(good, bad)

        val session = BisectSession(
            startPoint = bisectGateway.startPoint(),
            good = listOf(good),
            bad = bad,
            skipped = emptyList(),
            testing = null,
        )
        bisectGateway.saveSession(expected = null, session = session)
        return advance(session, survey)
    }

    /**
     * 지금 체크아웃된 검사 대상에 [verdict] 를 적용하고 다음 걸음으로 넘어간다.
     *
     * 읽은 세션을 그대로 `expected` 로 넘겨 기록 직전에 대조한다 — 같은 세션을 함께 읽은 다른 판정이
     * 먼저 기록했다면 여기서 거절돼야 한다. 덮어쓰면 두 판정 중 하나가 소리 없이 사라진다.
     *
     * @throws UndineException.StateViolation 진행 중이 아니거나, 검사 대상 없이 good/bad 를 판정할 때,
     *   또는 계산하는 동안 저장소의 세션이 바뀌었을 때
     */
    suspend fun mark(verdict: BisectVerdict): BisectResult {
        val session = requireInProgress()
        val testing = session.testing ?: return markWithoutTarget(session, verdict)

        val marked = session.marked(verdict, testing)
        bisectGateway.saveSession(expected = session, session = marked)
        return advance(marked, bisectGateway.surveyCandidates(marked.good, marked.bad))
    }

    /**
     * 탐색을 접고 시작 지점으로 되돌린다. detached HEAD 에서 시작했다면 그 커밋으로 돌아간다.
     *
     * 세션을 통째로 읽지 않고 [BisectGateway.clearSession] 에 맡긴다 — 참조가 반쯤 사라진 상태에서도
     * 시작 지점만 남아 있으면 되돌아갈 수 있어야 한다. 세션을 먼저 파싱하면 그런 상태에서 reset 까지
     * 함께 막혀 사용자가 빠져나올 길이 없어진다.
     *
     * @throws UndineException.StateViolation 진행 중인 세션이 없을 때
     */
    suspend fun reset() = bisectGateway.clearSession()

    /**
     * 검사 대상이 체크아웃돼 있지 않은 세션에서의 판정 요청.
     *
     * 미지원 구간에서 멈춘 세션이 이 상태다 — 후보를 고르지 못해 검사할 커밋이 없다.
     *
     * - good/bad 는 **거부한다.** 검사하지 않은 커밋에 판정을 붙이면 없는 근거를 지어내는 것이다.
     * - skip 은 **받는다.** "이 자리는 넘기고 다음 대상을 달라" 는 요청으로 읽고 후보를 다시 훑는다.
     *   여기서 예외를 던지면 미지원 세션이 reset 밖에 길이 없는 실패 상태가 된다.
     *
     * 구간이 여전히 미지원이면 같은 미지원 결과를 그대로 돌려준다 — 없는 진행을 지어내지 않는다.
     */
    private suspend fun markWithoutTarget(session: BisectSession, verdict: BisectVerdict): BisectResult {
        if (verdict != BisectVerdict.SKIP) throw UndineException.StateViolation(NOTHING_TO_MARK)
        return advance(session, bisectGateway.surveyCandidates(session.good, session.bad))
    }

    /**
     * 후보 구간을 보고 다음 걸음을 정한다.
     *
     * 검사할 대상이 남지 않은 상태는 두 가지로 갈린다 — 후보가 하나면 그것이 답이고(확정),
     * 둘 이상이면 skip 때문에 좁히지 못한 것이다(후보 목록).
     */
    private suspend fun advance(session: BisectSession, survey: CandidateSurvey): BisectResult =
        when (survey) {
            // 시작 시점에 걸러지지만 판정이 구간을 뒤집을 여지를 남기지 않으려 여기서도 닫는다.
            CandidateSurvey.BadIsAncestorOfGood ->
                BisectResult.Unsupported(BisectUnsupportedReason.GOOD_IS_NOT_ANCESTOR_OF_BAD, session.bad)

            is CandidateSurvey.NotLinear -> BisectResult.Unsupported(survey.reason, survey.at)

            is CandidateSurvey.Linear -> probeOrConclude(session, survey.range)
        }

    private suspend fun probeOrConclude(session: BisectSession, range: CandidateRange): BisectResult {
        val probe = range.nextProbe(session.skipped.toSet())
            ?: return conclude(range)
        // 체크아웃과 기록을 한 걸음으로 맡긴다 — 나누면 HEAD 와 세션이 서로 다른 커밋을 가리킬 틈이 생긴다.
        // [session] 은 방금 저장소에 기록된 상태라 그대로 대조 기준이 된다.
        bisectGateway.beginProbe(expected = session, probe = probe)
        return BisectResult.Testing(
            commit = probe,
            remainingCandidates = range.size,
            expectedRemainingChecks = range.expectedRemainingChecks,
        )
    }

    private fun conclude(range: CandidateRange): BisectResult =
        if (range.size == 1) BisectResult.FirstBad(range.commits.single())
        else BisectResult.Inconclusive(range.commits)

    private suspend fun requireInProgress(): BisectSession =
        bisectGateway.currentSession() ?: throw UndineException.StateViolation(NOT_IN_PROGRESS)
}
