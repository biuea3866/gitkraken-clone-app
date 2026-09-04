package dev.undine.bench

import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.graph.GraphLaneAssigner
import dev.undine.domain.graph.LaneCarry
import dev.undine.infrastructure.git.history.HistoryGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/** 측정 기준 참조. 생성 스크립트가 만드는 main 이력을 읽는다. */
private const val BENCH_REF = "refs/heads/main"

/** 한 페이지에 담는 커밋 수. 화면이 실제로 요청하는 크기와 같은 규모로 둔다. */
private const val BENCH_PAGE_SIZE = 200

/** 최대 측정 페이지 수. 저장소가 더 작으면 그 전에 멈춘다. */
private const val BENCH_PAGE_COUNT = 10

/** 한 페이지의 측정치. 이력 로드와 레인 배치를 **한 값으로 합치지 않는다** — 합치면 어느 쪽이 느려졌는지 모른다. */
private data class PageMeasurement(
    val page: Int,
    val commitCount: Int,
    val historyLoad: Duration,
    val laneAssign: Duration,
    val carriedInLanes: Int?,
    val carriedOutLanes: Int,
)

/**
 * 대형 저장소에서 **이력 페이지 로드**와 **레인 배치**의 비용을 나눠 잰다 (UND-88).
 *
 * 왜 나눠 재는가 — 두 값을 합쳐 놓으면 나중에 느려졌을 때 JGit 이력 읽기가 느려진 건지 레인
 * 알고리즘이 느려진 건지 구분할 수 없다. 회귀를 추적하려면 구간이 갈려 있어야 한다.
 *
 * **수치 합격선을 두지 않는다.** 기준선을 여러 번 보기 전에 숫자를 박으면 그 값이 근거 없이
 * 계약이 된다. 이 스펙은 재는 수단이고, 결과는 출력으로 남긴다.
 *
 * 벤치 저장소가 있을 때만 돈다 ([BenchRepositoryPresent]) — 일반 `./gradlew build` 와 CI 는
 * 대형 저장소를 만들지도, 이 측정을 돌리지도 않는다.
 *
 * ```bash
 * .agent/scripts/make-bench-repo.sh --commits 26000 --branches 800 --output /tmp/undine-bench
 * UNDINE_BENCH_REPO=/tmp/undine-bench ./gradlew :app:test --tests '*GraphHistoryBench*'
 * ```
 *
 * 환경 변수 **없이** `--tests '*GraphHistoryBench*'` 를 주면 Gradle 이 `No tests found` 로 실패한다.
 * 스펙이 꺼진 것이 원인이며 결함이 아니다 — 필터 없이 돌리면(`./gradlew build`) 그냥 건너뛴다.
 */
@EnabledIf(BenchRepositoryPresent::class)
class GraphHistoryBenchSpec : FunSpec({

    val repositoryPath = benchRepositoryPath()
    val gitAccess = GitAccess()
    val historyGateway = HistoryGatewayImpl(gitAccess)
    val refs = listOf(RefName(BENCH_REF))

    beforeSpec {
        val located = requireNotNull(repositoryPath) {
            "$BENCH_REPOSITORY_ENV 가 가리키는 벤치 저장소를 찾지 못했습니다"
        }
        gitAccess.open(RepositoryPath(located.toString())) { }
    }

    afterSpec {
        gitAccess.close()
        // 핸들을 정말 놓았는지 본다 — 닫지 않으면 뒤따르는 스펙이 이 저장소를 쥔 채 돈다.
        shouldThrow<UndineException.StateViolation> { historyGateway.load(refs, 0, 1) }
    }

    test("이력 로드와 레인 배치를 페이지마다 별도 구간으로 재고 carry 를 이어 넘긴다") {
        val measurements = measurePages(historyGateway, refs)
        reportMeasurements(measurements)

        measurements.shouldNotBeEmpty()
        measurements.forEach { measurement ->
            measurement.commitCount shouldBeGreaterThan 0
            measurement.historyLoad shouldBeGreaterThanOrEqualTo Duration.ZERO
            measurement.laneAssign shouldBeGreaterThanOrEqualTo Duration.ZERO
        }

        // 첫 페이지는 이어받을 것이 없고, 이후 페이지는 직전 페이지가 남긴 레인을 그대로 받는다.
        measurements.first().carriedInLanes.shouldBeNull()
        measurements.zipWithNext().forEach { (previous, next) ->
            next.carriedInLanes shouldBe previous.carriedOutLanes
        }
    }
})

/**
 * 페이지를 순서대로 읽으며 구간별로 잰다. 직전 페이지의 [LaneCarry] 를 다음 배치에 그대로 넘겨,
 * 페이지 경계에서 레인이 끊기지 않는 실제 호출 순서를 그대로 재현한다.
 */
private suspend fun measurePages(gateway: HistoryGateway, refs: List<RefName>): List<PageMeasurement> {
    val measurements = mutableListOf<PageMeasurement>()
    var carry: LaneCarry? = null
    var offset = 0
    var exhausted = false

    while (!exhausted && measurements.size < BENCH_PAGE_COUNT) {
        val loaded = measureTimedValue { gateway.load(refs, offset, BENCH_PAGE_SIZE) }
        val commits = loaded.value
        exhausted = commits.size < BENCH_PAGE_SIZE
        if (commits.isNotEmpty()) {
            val carriedIn = carry?.activeLaneCount
            val assigned = measureTimedValue { GraphLaneAssigner.assign(commits, carry) }
            val nextCarry = assigned.value.carry
            measurements += PageMeasurement(
                page = measurements.size + 1,
                commitCount = commits.size,
                historyLoad = loaded.duration,
                laneAssign = assigned.duration,
                carriedInLanes = carriedIn,
                carriedOutLanes = nextCarry.activeLaneCount,
            )
            carry = nextCarry
            offset += commits.size
        }
    }
    return measurements
}

/** 측정 결과를 사람이 읽을 표로 남긴다. 합격선이 아니라 **관찰값**이다. */
private fun reportMeasurements(measurements: List<PageMeasurement>) {
    println("[UND-88] 그래프 벤치 — 페이지당 $BENCH_PAGE_SIZE 커밋, 최대 $BENCH_PAGE_COUNT 페이지")
    println("page | commits | history.load | lane.assign | carry.in | carry.out")
    measurements.forEach { measurement ->
        println(
            listOf(
                measurement.page.toString().padStart(4),
                measurement.commitCount.toString().padStart(7),
                measurement.historyLoad.toString().padStart(12),
                measurement.laneAssign.toString().padStart(11),
                (measurement.carriedInLanes?.toString() ?: "-").padStart(8),
                measurement.carriedOutLanes.toString().padStart(9),
            ).joinToString(" | "),
        )
    }
    val historyTotal = measurements.fold(Duration.ZERO) { total, page -> total + page.historyLoad }
    val laneTotal = measurements.fold(Duration.ZERO) { total, page -> total + page.laneAssign }
    println("[UND-88] 합계 — history.load $historyTotal / lane.assign $laneTotal")
}
