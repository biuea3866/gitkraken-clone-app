package dev.undine.domain.bisect

import dev.undine.domain.UndineException
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** 오래된 것부터 8건. 커밋 seed 1..8 이 그대로 이력 순서다. */
private val HISTORY = (1..8).map(::commitId)

private val MERGE_IN_RANGE = CandidateSurvey.NotLinear(
    BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE,
    commitId(5),
)

private const val NOTHING_TO_MARK = "판정할 검사 대상이 체크아웃돼 있지 않습니다"

/**
 * 탐색 규칙 — 저장소 없이 도메인 대역으로 검증한다.
 *
 * 여기서 보는 것은 "어디를 다음에 검사하고 언제 확정하는가" 이고, 실제 Git 동작은
 * `BisectGatewayImplSpec` 이 임시 저장소로 본다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 3).
 */
class BisectServiceSpec : FunSpec({

    test("시작하면 후보 구간의 가운데를 체크아웃하고 남은 횟수를 알려준다") {
        val gateway = FakeBisectGateway(HISTORY)

        val result = BisectService(gateway).start(good = commitId(1), bad = commitId(8))

        // 후보는 (1, 8] 인 7건이고 검사 대상 6건의 가운데가 5 다.
        val testing = result.shouldBeInstanceOf<BisectResult.Testing>()
        testing.commit shouldBe commitId(5)
        testing.remainingCandidates shouldBe 7
        testing.expectedRemainingChecks shouldBe 3
        gateway.checkedOut shouldContainExactly listOf(commitId(5))
    }

    test("시작 시점의 자리를 세션에 기록해 둔다 — reset 이 돌아갈 곳이다") {
        val gateway = FakeBisectGateway(HISTORY)

        BisectService(gateway).start(good = commitId(1), bad = commitId(8))

        gateway.currentSession()?.startPoint shouldBe START_BRANCH
        gateway.currentSession()?.testing shouldBe commitId(5)
    }

    test("bad 가 good 의 조상이면 경고만 돌려주고 세션을 시작하지 않는다") {
        val gateway = FakeBisectGateway(HISTORY)

        val result = BisectService(gateway).start(good = commitId(6), bad = commitId(2))

        result shouldBe BisectResult.ReversedRange(good = commitId(6), bad = commitId(2))
        // 좁힐 구간이 없는 세션을 열어 두면 사용자가 되돌릴 일만 는다.
        gateway.currentSession() shouldBe null
        gateway.checkedOut.shouldBeEmpty()
    }

    test("good 과 bad 사이에 커밋이 없으면 즉시 bad 로 확정한다") {
        val gateway = FakeBisectGateway(HISTORY)

        val result = BisectService(gateway).start(good = commitId(4), bad = commitId(5))

        result shouldBe BisectResult.FirstBad(commitId(5))
        gateway.checkedOut.shouldBeEmpty()
    }

    test("good/bad 를 반복 판정하면 최초 나쁜 커밋 하나로 확정된다") {
        // 실제 원인은 4 다 — 4 이상이 bad, 3 이하가 good 인 이력이다.
        val service = BisectService(FakeBisectGateway(HISTORY))
        service.start(good = commitId(1), bad = commitId(8))

        // 5(bad) → 후보 (1,5] 의 가운데 3
        service.mark(BisectVerdict.BAD).shouldBeInstanceOf<BisectResult.Testing>()
            .commit shouldBe commitId(3)
        // 3(good) → 후보 (3,5] 의 유일한 대상 4
        service.mark(BisectVerdict.GOOD).shouldBeInstanceOf<BisectResult.Testing>()
            .commit shouldBe commitId(4)

        service.mark(BisectVerdict.BAD) shouldBe BisectResult.FirstBad(commitId(4))
    }

    test("skip 으로 검사 대상이 사라지면 단정하지 않고 후보 목록을 돌려준다") {
        val service = BisectService(FakeBisectGateway(HISTORY))
        service.start(good = commitId(1), bad = commitId(3))

        // 후보 (1, 3] = [2, 3] 이고 검사 대상은 2 하나뿐이다. 그것을 건너뛰면 좁힐 수 없다.
        val result = service.mark(BisectVerdict.SKIP)

        result.shouldBeInstanceOf<BisectResult.Inconclusive>()
            .candidates shouldContainExactly listOf(commitId(2), commitId(3))
    }

    test("건너뛴 커밋도 후보로 남는다 — 최초 나쁜 커밋일 수 있다") {
        val gateway = FakeBisectGateway(HISTORY)
        val service = BisectService(gateway)
        service.start(good = commitId(1), bad = commitId(3))

        service.mark(BisectVerdict.SKIP)

        gateway.currentSession()?.skipped shouldContainExactly listOf(commitId(2))
    }

    test("병합 커밋이 낀 구간은 미지원으로 알리고 세션은 살아 있다") {
        val gateway = FakeBisectGateway(HISTORY, notLinear = MERGE_IN_RANGE)

        val result = BisectService(gateway).start(good = commitId(1), bad = commitId(8))

        result shouldBe BisectResult.Unsupported(BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE, commitId(5))
        // 실패 상태로 만들면 사용자가 skip 도 reset 도 하지 못한다.
        gateway.currentSession() shouldNotBe null
        // 근거 없는 후보를 고르지 않았다 — 체크아웃도 하지 않는다.
        gateway.checkedOut.shouldBeEmpty()
    }

    test("미지원 뒤에도 reset 으로 빠져나올 수 있다") {
        val gateway = FakeBisectGateway(HISTORY, notLinear = MERGE_IN_RANGE)
        val service = BisectService(gateway)
        service.start(good = commitId(1), bad = commitId(8))

        service.reset()

        gateway.cleared shouldBe true
    }

    test("미지원 뒤 skip 은 거부되지 않는다 — 실패 상태로 만들면 빠져나갈 길이 reset 뿐이다") {
        val gateway = FakeBisectGateway(HISTORY, notLinear = MERGE_IN_RANGE)
        val service = BisectService(gateway)
        service.start(good = commitId(1), bad = commitId(8))

        val result = service.mark(BisectVerdict.SKIP)

        // 구간이 여전히 미지원이라 없는 진행을 지어내지 않고 같은 사유를 그대로 돌려준다.
        result shouldBe BisectResult.Unsupported(BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE, commitId(5))
        gateway.currentSession() shouldNotBe null
        gateway.checkedOut.shouldBeEmpty()
    }

    test("검사 대상이 없을 때 good/bad 판정은 거부된다 — 검사하지 않은 커밋에 판정을 붙일 수 없다") {
        val service = BisectService(FakeBisectGateway(HISTORY, notLinear = MERGE_IN_RANGE))
        service.start(good = commitId(1), bad = commitId(8))

        // 세션이 살아 있다는 것을 사유로 확인한다 — 진행 중이 아니라면 다른 메시지가 나온다.
        shouldThrow<UndineException.StateViolation> { service.mark(BisectVerdict.GOOD) }
            .detail shouldBe NOTHING_TO_MARK
        shouldThrow<UndineException.StateViolation> { service.mark(BisectVerdict.BAD) }
            .detail shouldBe NOTHING_TO_MARK
    }

    test("검사 대상 기록이 없는 세션에서 skip 은 대상을 다시 고른다") {
        // 외부 도구가 남긴 세션처럼 진행 중이지만 검사 대상이 기록되지 않은 상태다.
        val gateway = FakeBisectGateway(HISTORY, session = restoredSession().copy(testing = null))

        val result = BisectService(gateway).mark(BisectVerdict.SKIP)

        result.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(5)
        gateway.currentSession()?.testing shouldBe commitId(5)
    }

    test("reset 은 세션 상태를 지운다") {
        val gateway = FakeBisectGateway(HISTORY)
        val service = BisectService(gateway)
        service.start(good = commitId(1), bad = commitId(8))

        service.reset()

        gateway.cleared shouldBe true
        gateway.currentSession() shouldBe null
    }

    test("진행 중인 세션이 있으면 새로 시작하지 않는다") {
        val service = BisectService(FakeBisectGateway(HISTORY))
        service.start(good = commitId(1), bad = commitId(8))

        shouldThrow<UndineException.StateViolation> { service.start(commitId(2), commitId(7)) }
    }

    test("진행 중이 아니면 판정도 reset 도 거부한다") {
        val service = BisectService(FakeBisectGateway(HISTORY))

        shouldThrow<UndineException.StateViolation> { service.mark(BisectVerdict.GOOD) }
        shouldThrow<UndineException.StateViolation> { service.reset() }
    }

    test("저장소에 남은 세션을 그대로 이어 읽는다 — 앱을 다시 켠 상황이다") {
        val restored = restoredSession()

        BisectService(FakeBisectGateway(HISTORY, session = restored)).currentSession() shouldBe restored
    }

    test("복원된 세션에서 판정을 이어가면 좁혀진 구간으로 계산한다") {
        val gateway = FakeBisectGateway(HISTORY, session = restoredSession())

        val result = BisectService(gateway).mark(BisectVerdict.BAD)

        // bad 가 5 로 좁혀져 후보 (1, 5] 인 4건, 검사 대상 3건의 가운데는 3 이다.
        result.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(3)
    }
})

/** 앱을 껐다 켠 뒤 저장소에서 읽어 온 세션. 5 를 검사하다 멈춘 상태다. */
private fun restoredSession(): BisectSession = BisectSession(
    startPoint = START_BRANCH,
    good = listOf(commitId(1)),
    bad = commitId(8),
    skipped = emptyList(),
    testing = commitId(5),
)
