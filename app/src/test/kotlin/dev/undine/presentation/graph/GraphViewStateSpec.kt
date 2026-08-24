package dev.undine.presentation.graph

import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.graph.EdgeKind
import dev.undine.testsupport.HistoryRequest
import dev.undine.testsupport.RecordingHistoryGateway
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private val REFS = listOf(RefName("refs/heads/main"))
private const val PAGE_SIZE = 2

private fun stateOf(
    commits: List<Commit> = emptyList(),
    failure: UndineException? = null,
    gate: CompletableDeferred<Unit>? = null,
    gateFrom: Int = 0,
    pageSize: Int = PAGE_SIZE,
): Pair<GraphViewState, RecordingHistoryGateway> {
    val gateway = RecordingHistoryGateway(
        commits = commits,
        failure = failure,
        gate = gate,
        gateFrom = gateFrom,
    )
    val state = GraphViewState(
        loadCommitHistory = LoadCommitHistoryUseCase(gateway),
        refs = REFS,
        pageSize = pageSize,
    )
    return state to gateway
}

/**
 * 그래프 화면의 상태 홀더 — 페이징 offset, 페이지 경계 carry, 실패·취소 구분, 선택 상태.
 *
 * 레인 배치 자체의 정확성은 `GraphLaneAssignerSpec` 이 검증한다. 여기서는 **페이지를 이어 붙일 때
 * 그 배치가 끊기지 않는가**를 본다.
 */
class GraphViewStateSpec : FunSpec({

    test("첫 페이지는 offset 0 으로 요청하고 로드가 끝나면 Loaded 가 된다") {
        val (state, gateway) = stateOf(listOf(commit(1, 2), commit(2, 3), commit(3)))

        state.status shouldBe GraphLoadStatus.Idle
        state.loadInitialPage()

        gateway.requests shouldContainExactly listOf(HistoryRequest(REFS, 0, PAGE_SIZE))
        state.status shouldBe GraphLoadStatus.Loaded
        state.rows shouldHaveSize PAGE_SIZE
        state.rows.map { it.commit.id } shouldContainExactly listOf(commitId(1), commitId(2))
    }

    test("첫 페이지를 두 번 호출해도 Gateway 요청은 한 번뿐이다") {
        val (state, gateway) = stateOf(listOf(commit(1, 2), commit(2)))

        state.loadInitialPage()
        state.loadInitialPage()

        gateway.requests shouldHaveSize 1
    }

    test("후속 페이지는 이미 쌓인 행 수를 offset 으로 요청한다") {
        val (state, gateway) = stateOf(listOf(commit(1, 2), commit(2, 3), commit(3, 4), commit(4)))

        state.loadInitialPage()
        state.loadNextPage()

        gateway.requests shouldContainExactly listOf(
            HistoryRequest(REFS, 0, PAGE_SIZE),
            HistoryRequest(REFS, PAGE_SIZE, PAGE_SIZE),
        )
        state.rows.map { it.commit.id } shouldContainExactly
            listOf(commitId(1), commitId(2), commitId(3), commitId(4))
    }

    test("페이지 경계에서 직전 carry 를 넘겨 레인 통과선이 끊기지 않는다") {
        // 1(레인0) -> 4, 2(레인1) -> 3 -> 4. 2·3 이 다음 페이지로 넘어가도 레인 0 통과선이 이어져야 한다.
        val commits = listOf(commit(1, 4), commit(2, 3), commit(3, 4), commit(4))
        val (state, _) = stateOf(commits)

        state.loadInitialPage()
        state.loadNextPage()

        state.rows.map { it.row.lane } shouldContainExactly listOf(0, 1, 1, 0)
        // 두 번째 페이지 첫 행(커밋 3)이 carry 없이 배치됐다면 레인 0 통과선이 사라진다.
        state.rows[2].row.passThrough.map { it.lane } shouldContainExactly listOf(0)
        state.rows[2].row.lane shouldBe 1
        state.laneCount shouldBe 2
    }

    test("carry 를 넘기지 않았다면 두 번째 페이지의 레인이 0 으로 되돌아간다") {
        // 위 테스트가 carry 전달을 실제로 검증하는지 보이는 대조군이다.
        val secondPageAlone = dev.undine.domain.graph.GraphLaneAssigner
            .assign(listOf(commit(3, 4), commit(4)))

        secondPageAlone.rows[0].lane shouldBe 0
        secondPageAlone.rows[0].passThrough.shouldBeEmpty()
    }

    test("병합 커밋 행은 부모 연결을 모두 보존한다") {
        val (state, _) = stateOf(
            commits = listOf(commit(1, 2, 3), commit(2, 4), commit(3, 4), commit(4)),
            pageSize = 4,
        )

        state.loadInitialPage()

        val mergeRow = state.rows.first().row
        mergeRow.parents shouldHaveSize 2
        mergeRow.parents.map { it.kind } shouldContainExactly listOf(EdgeKind.STRAIGHT, EdgeKind.MERGE)
    }

    test("페이지 밖 부모로 이어지는 연결은 항목이 남고 이을 레인만 비어 있다") {
        val (state, _) = stateOf(listOf(commit(1, 2), commit(2, 3), commit(3)))

        state.loadInitialPage()

        val lastRow = state.rows.last().row
        lastRow.parents shouldHaveSize 1
        lastRow.parents.single().toLane shouldBe dev.undine.domain.graph.LaneEdge.NO_LANE
        GraphLaneGeometry.isDrawable(lastRow.parents.single()) shouldBe false
    }

    test("마지막 페이지에 도달하면 더 이상 Gateway 를 부르지 않는다") {
        val (state, gateway) = stateOf(listOf(commit(1, 2), commit(2), commit(3)))

        state.loadInitialPage()
        state.loadNextPage()
        state.loadNextPage()

        // 3건 중 2건 + 1건 을 받아 페이지가 덜 찼으므로 이력이 끝났다.
        gateway.requests shouldHaveSize 2
        state.rows shouldHaveSize 3
    }

    test("커밋이 0건이면 행이 비고 실패가 아닌 Loaded 로 끝난다") {
        val (state, _) = stateOf(emptyList())

        state.loadInitialPage()

        state.rows.shouldBeEmpty()
        state.status shouldBe GraphLoadStatus.Loaded
        state.laneCount shouldBe 0
    }

    test("이력 로딩 실패는 빈 목록이 아니라 실패 상태로 남는다") {
        val failure = UndineException.GitOperationFailed("history")
        val (state, _) = stateOf(failure = failure)

        state.loadInitialPage()

        state.rows.shouldBeEmpty()
        val status = state.status.shouldBeInstanceOf<GraphLoadStatus.Failed>()
        status.failure shouldBeSameInstanceAs failure
    }

    test("진행 중인 페이지 요청이 취소되면 상태가 성공·빈 결과로 바뀌지 않는다") {
        val gate = CompletableDeferred<Unit>()
        val (state, gateway) = stateOf(commits = listOf(commit(1, 2), commit(2)), gate = gate)

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { state.loadInitialPage() }
        job.cancel()
        job.join()
        yield()

        job.isCancelled shouldBe true
        gateway.requests shouldHaveSize 1
        state.rows.shouldBeEmpty()
        state.status shouldBe GraphLoadStatus.Idle
    }

    test("진행 중인 다음 페이지 요청이 취소되면 이미 쌓인 행과 Loaded 상태가 유지된다") {
        // 빠른 스크롤로 후속 페이지 요청이 취소되는 경로다. 첫 페이지 결과가 사라지거나
        // 실패·빈 상태로 뒤바뀌면 안 된다.
        val gate = CompletableDeferred<Unit>()
        val commits = listOf(commit(1, 2), commit(2, 3), commit(3, 4), commit(4))
        val (state, gateway) = stateOf(commits = commits, gate = gate, gateFrom = 1)

        state.loadInitialPage()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { state.loadNextPage() }
        job.cancel()
        job.join()
        yield()

        job.isCancelled shouldBe true
        gateway.requests.map { it.offset } shouldContainExactly listOf(0, PAGE_SIZE)
        // 새 행이 붙지 않고 첫 페이지가 그대로 남는다.
        state.rows.map { it.commit.id } shouldContainExactly listOf(commitId(1), commitId(2))
        state.status shouldBe GraphLoadStatus.Loaded
    }

    test("다음 페이지 요청이 취소된 뒤 같은 offset 을 다시 요청할 수 있다") {
        val gate = CompletableDeferred<Unit>()
        val commits = listOf(commit(1, 2), commit(2, 3), commit(3, 4), commit(4))
        val (state, gateway) = stateOf(commits = commits, gate = gate, gateFrom = 1)

        state.loadInitialPage()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { state.loadNextPage() }
        job.cancel()
        job.join()
        gate.complete(Unit)

        state.loadNextPage()

        gateway.requests.map { it.offset } shouldContainExactly listOf(0, PAGE_SIZE, PAGE_SIZE)
        state.rows.map { it.commit.id } shouldContainExactly
            listOf(commitId(1), commitId(2), commitId(3), commitId(4))
        state.status shouldBe GraphLoadStatus.Loaded
    }

    test("취소된 뒤에도 첫 페이지를 다시 요청할 수 있다") {
        val gate = CompletableDeferred<Unit>()
        val (state, gateway) = stateOf(commits = listOf(commit(1, 2), commit(2)), gate = gate)

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { state.loadInitialPage() }
        job.cancel()
        job.join()
        gate.complete(Unit)

        state.loadInitialPage()

        gateway.requests shouldHaveSize 2
        state.status shouldBe GraphLoadStatus.Loaded
        state.rows shouldHaveSize 2
    }

    test("행을 고르면 선택 상태와 선택된 커밋이 함께 노출된다") {
        val (state, _) = stateOf(listOf(commit(1, 2), commit(2)))
        state.loadInitialPage()

        state.selectedCommitId shouldBe null
        state.selectedCommit shouldBe null

        state.selectCommit(commitId(2))

        state.selectedCommitId shouldBe commitId(2)
        state.selectedCommit?.id shouldBe commitId(2)

        state.selectCommit(null)
        state.selectedCommit shouldBe null
    }

    test("레인 수는 페이지가 쌓이며 가장 넓은 레인까지 커진다") {
        val (state, _) = stateOf(listOf(commit(1, 4), commit(2, 3), commit(3, 4), commit(4)))

        state.loadInitialPage()
        val afterFirstPage = state.laneCount
        state.loadNextPage()

        afterFirstPage shouldBeGreaterThan 0
        state.laneCount shouldBe 2
    }
})
