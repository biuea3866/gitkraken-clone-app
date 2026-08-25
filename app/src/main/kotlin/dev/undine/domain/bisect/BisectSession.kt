package dev.undine.domain.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RefName

/** 검사 대상 커밋에 대한 사용자의 판정. */
enum class BisectVerdict {

    /** 이 커밋에는 문제가 없다. 후보 구간의 아래쪽이 잘린다. */
    GOOD,

    /** 이 커밋에 문제가 있다. 후보 구간의 위쪽이 잘린다. */
    BAD,

    /**
     * 판정할 수 없다 (빌드가 깨졌거나 재현 자체가 불가능). 후보에서 빼지 않고 **건너뛰기만** 한다 —
     * 이 커밋이 최초 나쁜 커밋일 가능성은 그대로 남는다.
     */
    SKIP,
}

/**
 * 탐색을 시작한 지점. reset 이 여기로 되돌린다.
 *
 * 브랜치 이름과 커밋을 갈라 두는 이유는 **detached HEAD 에서 시작한 세션**도 정확히 복구하기
 * 위해서다. 브랜치가 없는데 아무 브랜치로 돌려놓으면 사용자가 있던 자리가 아니다.
 */
sealed interface BisectStartPoint {

    /** [name] 은 완전한 참조 이름(`refs/heads/main`)이다. 표시용 축약은 presentation 이 한다. */
    data class Branch(val name: RefName) : BisectStartPoint

    /** 브랜치 없이 커밋을 직접 가리키고 있었다. */
    data class Detached(val commit: CommitId) : BisectStartPoint
}

/**
 * 진행 중인 이분 탐색 세션.
 *
 * 이 값은 **저장소 안**(`.git/` 하위 표준 bisect 상태)에 있다 — 앱 설정에 두면 외부 git CLI 로
 * 같은 저장소를 오갈 때 두 벌의 상태가 어긋난다. 그래서 앱을 껐다 켜도 그대로 복원된다.
 *
 * @param good 지금까지 문제없다고 판정된 커밋들. 후보 구간의 아래 경계다.
 * @param bad 지금까지 확인된 가장 오래된 나쁜 커밋. 후보 구간의 위 경계이자 그 자체로 후보다.
 * @param skipped 판정할 수 없어 건너뛴 커밋들. 후보에서 빠지지 않고 검사 대상에서만 빠진다.
 * @param testing 지금 체크아웃돼 판정을 기다리는 커밋. 아직 고르지 않았으면 null 이다.
 */
data class BisectSession(
    val startPoint: BisectStartPoint,
    val good: List<CommitId>,
    val bad: CommitId,
    val skipped: List<CommitId>,
    val testing: CommitId?,
) {

    /**
     * [commit] 에 [verdict] 를 적용한 다음 세션. 순수 상태 전이라 저장소를 보지 않는다.
     *
     * 판정 뒤 [testing] 은 비운다 — 다음 대상은 후보 구간을 다시 계산해야 정해진다.
     */
    fun marked(verdict: BisectVerdict, commit: CommitId): BisectSession = when (verdict) {
        BisectVerdict.GOOD -> copy(good = (good + commit).distinct(), testing = null)
        BisectVerdict.BAD -> copy(bad = commit, testing = null)
        BisectVerdict.SKIP -> copy(skipped = (skipped + commit).distinct(), testing = null)
    }
}
