package dev.undine.domain.bisect

import dev.undine.domain.CommitId
import kotlin.math.abs

/**
 * 아직 "최초 나쁜 커밋" 일 수 있는 후보 구간. **오래된 것부터** 정렬돼 있다.
 *
 * 마지막 원소가 지금의 bad 다 — bad 자신도 후보다. good 바로 다음 커밋이 bad 라면 답은 bad 이므로
 * 후보에서 빼면 확정할 대상이 사라진다.
 *
 * 이 타입은 **저장소를 모른다.** 다음 검사 대상과 남은 횟수 계산이 순수 함수라서 임시 저장소 없이
 * 단위 검증된다 ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 3).
 */
data class CandidateRange(val commits: List<CommitId>) {

    val size: Int get() = commits.size

    /**
     * 아직 판정하지 않은 검사 대상. **bad 는 제외한다** — 이미 나쁘다고 알고 있어 검사할 이유가 없다.
     * 비어 있으면 더 좁힐 수 없다는 뜻이다.
     */
    val testable: List<CommitId> get() = commits.dropLast(1)

    /**
     * 앞으로 몇 번 더 검사해야 하는지의 추정치. 한 번 검사할 때마다 후보가 절반이 되므로
     * `ceil(log2(size))` 다 — 후보 1건이면 이미 확정이라 0, 2건이면 1, 8건이면 3이다.
     *
     * skip 은 계산에 넣지 않는다. 건너뛴 커밋이 몇 번의 검사를 더 만들지는 그때 알 수 없고,
     * 추정치를 비관적으로 부풀리면 사용자가 진행 정도를 오해한다.
     */
    val expectedRemainingChecks: Int
        get() = if (size <= 1) 0 else Int.SIZE_BITS - Integer.numberOfLeadingZeros(size - 1)

    /**
     * 다음 검사 대상. 구간의 **가운데**를 골라 한 번에 절반을 걷어낸다.
     *
     * 가운데가 [skipped] 면 가운데에서 가까운 순서로 바깥쪽으로 옮긴다 — 건너뛴 커밋 때문에 탐색이
     * 멈추면 안 되지만, 가운데에서 멀어질수록 걷어내는 양이 줄기 때문에 가까운 쪽을 먼저 쓴다.
     *
     * 검사할 대상이 남지 않았으면 null 이다. 그 상태가 "확정" 인지 "skip 으로 좁히지 못함" 인지는
     * [BisectService] 가 후보 수로 판단한다.
     */
    fun nextProbe(skipped: Set<CommitId>): CommitId? {
        val testable = testable
        if (testable.isEmpty()) return null
        val middle = testable.size / 2
        return testable.indices
            .sortedBy { index -> abs(index - middle) }
            .map { index -> testable[index] }
            .firstOrNull { candidate -> candidate !in skipped }
    }
}

/**
 * good 과 bad 사이를 훑은 결과. 저장소 질의라 [BisectGateway] 가 답한다.
 *
 * 1차 구현은 **선형 이력만** 계산한다. 못 하는 경우를 [Linear] 의 빈 결과로 뭉개지 않고 타입으로
 * 갈라 두는 이유는, 조용히 틀린 후보를 고르는 것이 못 한다고 말하는 것보다 나쁘기 때문이다.
 */
sealed interface CandidateSurvey {

    /** good → bad 가 단일 경로다. [range] 는 오래된 것부터 정렬된 후보다. */
    data class Linear(val range: CandidateRange) : CandidateSurvey

    /**
     * bad 가 good 의 조상이다 — good 과 bad 를 반대로 지정한 것으로 보인다.
     *
     * 흔한 실수라 실패가 아니라 경고로 다룬다. 좁힐 구간 자체가 없으므로 탐색은 시작하지 않는다.
     */
    data object BadIsAncestorOfGood : CandidateSurvey

    /**
     * 1차 구현이 계산하지 않는 이력이다. [at] 은 그렇게 판정한 커밋(있을 때).
     */
    data class NotLinear(val reason: BisectUnsupportedReason, val at: CommitId?) : CandidateSurvey
}

/** 이분 탐색을 계산하지 못한 사유. 화면이 사유마다 다르게 안내할 수 있도록 닫아 둔다. */
enum class BisectUnsupportedReason {

    /**
     * 후보 구간에 병합 커밋이 있어 경로가 갈린다. git 의 조상 집합 기준 중앙값을 재현하지 않으므로
     * 여기서 후보를 고르면 근거 없는 선택이 된다.
     */
    MERGE_COMMIT_IN_RANGE,

    /** good 이 bad 의 조상이 아니다 — 두 커밋이 이어지지 않아 좁힐 구간을 정의할 수 없다. */
    GOOD_IS_NOT_ANCESTOR_OF_BAD,
}
