package dev.undine.domain.bisect

import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** 오래된 것부터 [count] 개. 마지막이 bad 다. */
private fun rangeOf(count: Int): CandidateRange =
    CandidateRange((1..count).map { seed -> commitId(seed) })

/**
 * 후보 구간 계산 — **저장소 없이** 검증한다.
 *
 * 다음 검사 대상 선택과 남은 횟수 추정은 순수 함수라, 임시 저장소를 만들면 느리기만 하고 검증력은
 * 늘지 않는다 ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 3).
 */
class CandidateRangeSpec : FunSpec({

    test("후보가 하나면 더 검사할 것이 없고 남은 횟수도 0 이다") {
        val range = rangeOf(1)

        range.testable.shouldBeEmpty()
        range.nextProbe(emptySet()) shouldBe null
        range.expectedRemainingChecks shouldBe 0
    }

    test("bad 는 검사 대상에서 빠진다 — 이미 나쁘다고 알고 있다") {
        val range = rangeOf(4)

        range.testable shouldBe listOf(commitId(1), commitId(2), commitId(3))
    }

    test("다음 검사 대상은 검사 가능한 구간의 가운데다") {
        // 후보 [1,2,3,bad=4] 에서 검사 대상은 1..3, 가운데는 2 다.
        rangeOf(4).nextProbe(emptySet()) shouldBe commitId(2)
    }

    test("후보가 둘이면 그 사이의 유일한 대상을 검사한다") {
        rangeOf(2).nextProbe(emptySet()) shouldBe commitId(1)
    }

    test("남은 검사 횟수는 후보 수의 log2 올림이다") {
        // 한 번 검사할 때마다 후보가 절반이 된다 — "앞으로 약 N 번" 표시의 근거다.
        listOf(1 to 0, 2 to 1, 3 to 2, 4 to 2, 5 to 3, 8 to 3, 9 to 4, 16 to 4, 17 to 5)
            .forEach { (candidates, checks) ->
                rangeOf(candidates).expectedRemainingChecks shouldBe checks
            }
    }

    test("가운데가 건너뛴 커밋이면 가운데에서 가장 가까운 대상을 고른다") {
        // 걷어내는 양이 가장 큰 자리부터 순서대로 물러난다.
        rangeOf(6).nextProbe(setOf(commitId(3))) shouldBe commitId(2)
    }

    test("가운데 양옆까지 건너뛰었으면 더 바깥으로 물러난다") {
        rangeOf(6).nextProbe(setOf(commitId(3), commitId(2), commitId(4))) shouldBe commitId(1)
    }

    test("검사 대상이 전부 건너뛴 커밋이면 다음 대상이 없다") {
        // 여기서 후보 목록으로 답할지 확정할지는 BisectService 가 후보 수로 판단한다.
        val range = rangeOf(3)

        range.nextProbe(setOf(commitId(1), commitId(2))) shouldBe null
    }

    test("구간 밖의 건너뛴 커밋은 선택에 영향을 주지 않는다") {
        // bad 가 좁혀지면 예전 skip 이 구간 밖으로 밀려난다 — 그 값 때문에 대상이 바뀌면 안 된다.
        rangeOf(4).nextProbe(setOf(commitId(99))) shouldBe commitId(2)
    }
})
