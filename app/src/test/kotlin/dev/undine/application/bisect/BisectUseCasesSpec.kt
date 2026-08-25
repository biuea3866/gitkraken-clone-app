package dev.undine.application.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectGateway
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectStartPoint
import dev.undine.domain.bisect.BisectUnsupportedReason
import dev.undine.domain.bisect.BisectVerdict
import dev.undine.domain.bisect.CandidateRange
import dev.undine.domain.bisect.CandidateSurvey
import dev.undine.testsupport.commitId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val START_BRANCH: BisectStartPoint = BisectStartPoint.Branch(RefName("refs/heads/main"))

/**
 * UseCase 는 [BisectService] 결과를 **그대로** 올린다.
 *
 * 여기서 확인하는 것은 배선뿐이다 — 결과를 바꾸거나 삼키면 화면이 다음에 무엇을 할 수 있는지
 * 알 수 없게 된다. DI 조립과 화면 호출 경로는 이 티켓 범위 밖이라 검증하지 않는다.
 */
class BisectUseCasesSpec : BehaviorSpec({

    given("선형 이력 저장소") {
        `when`("good/bad 로 시작하면") {
            then("서비스가 고른 검사 대상이 그대로 올라온다") {
                val service = BisectService(ScriptedBisectGateway())

                val result = StartBisectUseCase(service).execute(commitId(1), commitId(8))

                result.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(5)
            }
        }

        `when`("이미 진행 중인데 다시 시작하면") {
            then("상태 위반이 삼켜지지 않고 그대로 올라온다") {
                val service = BisectService(ScriptedBisectGateway(session = sessionTesting(commitId(5))))

                shouldThrow<UndineException.StateViolation> {
                    StartBisectUseCase(service).execute(commitId(1), commitId(8))
                }
            }
        }
    }

    given("검사 대상이 체크아웃된 세션") {
        `when`("bad 로 판정하면") {
            then("좁혀진 구간의 다음 대상이 올라온다") {
                val service = BisectService(ScriptedBisectGateway(session = sessionTesting(commitId(5))))

                val result = MarkBisectUseCase(service).execute(BisectVerdict.BAD)

                result.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(3)
            }
        }

        `when`("good 으로 판정하면") {
            then("아래쪽이 잘린 구간의 다음 대상이 올라온다") {
                val service = BisectService(ScriptedBisectGateway(session = sessionTesting(commitId(5))))

                val result = MarkBisectUseCase(service).execute(BisectVerdict.GOOD)

                // good 이 c5 로 올라가 후보는 (c5, c8] — 검사 대상 [c6, c7] 의 가운데는 c7 이다.
                result.shouldBeInstanceOf<BisectResult.Testing>().commit shouldBe commitId(7)
            }
        }

        `when`("skip 으로 후보가 분할되면") {
            then("단일 커밋이 아니라 후보 목록이 올라온다") {
                val gateway = ScriptedBisectGateway(session = sessionTesting(commitId(2), bad = commitId(3)))
                val service = BisectService(gateway)

                val result = MarkBisectUseCase(service).execute(BisectVerdict.SKIP)

                result.shouldBeInstanceOf<BisectResult.Inconclusive>()
                    .candidates shouldContainExactly listOf(commitId(2), commitId(3))
            }
        }

        `when`("reset 을 요청하면") {
            then("세션이 지워진다") {
                val gateway = ScriptedBisectGateway(session = sessionTesting(commitId(5)))

                ResetBisectUseCase(BisectService(gateway)).execute()

                gateway.cleared shouldBe true
            }
        }
    }

    given("앱을 다시 켠 상황") {
        `when`("진행 중 세션이 저장소에 남아 있으면") {
            then("그 세션이 그대로 복원된다") {
                val restored = sessionTesting(commitId(5))
                val service = BisectService(ScriptedBisectGateway(session = restored))

                RestoreBisectSessionUseCase(service).execute() shouldBe restored
            }
        }

        `when`("진행 중 세션이 없으면") {
            then("실패가 아니라 null 이다") {
                val service = BisectService(ScriptedBisectGateway())

                RestoreBisectSessionUseCase(service).execute() shouldBe null
            }
        }
    }

    given("병합 커밋이 낀 구간") {
        `when`("판정을 이어가면") {
            then("미지원 결과가 그대로 올라온다") {
                val gateway = ScriptedBisectGateway(
                    session = sessionTesting(commitId(5)),
                    notLinear = CandidateSurvey.NotLinear(
                        BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE,
                        commitId(6),
                    ),
                )

                val result = MarkBisectUseCase(BisectService(gateway)).execute(BisectVerdict.BAD)

                result shouldBe BisectResult.Unsupported(
                    BisectUnsupportedReason.MERGE_COMMIT_IN_RANGE,
                    commitId(6),
                )
            }
        }
    }
})

private fun sessionTesting(testing: CommitId, bad: CommitId = commitId(8)): BisectSession = BisectSession(
    startPoint = START_BRANCH,
    good = listOf(commitId(1)),
    bad = bad,
    skipped = emptyList(),
    testing = testing,
)

/**
 * 8건짜리 선형 이력을 흉내 내는 domain interface 대역.
 *
 * `domain/bisect` 의 대역을 그대로 쓰지 않는 이유는 테스트 소스가 다른 패키지의 internal 을
 * 참조하게 만들지 않기 위해서다 — 실제 Git 동작은 `BisectGatewayImplSpec` 이 본다.
 */
private class ScriptedBisectGateway(
    private var session: BisectSession? = null,
    private val notLinear: CandidateSurvey.NotLinear? = null,
) : BisectGateway {

    private val history = (1..8).map(::commitId)

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
        this.session = expected.copy(testing = probe)
    }

    override suspend fun clearSession() {
        cleared = true
        session = null
    }

    /** 구현과 같은 계약이다 — 읽은 세션과 다르면 거절한다. */
    private fun requireUnchanged(expected: BisectSession?) {
        if (session != expected) throw UndineException.StateViolation("이분 탐색 상태가 바뀌었습니다")
    }
}
