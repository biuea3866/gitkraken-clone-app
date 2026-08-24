package dev.undine.presentation.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.undine.application.graph.LoadCommitHistoryUseCase
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.graph.GraphLaneAssigner
import dev.undine.domain.graph.LaneCarry
import kotlinx.coroutines.CancellationException

/** 한 번에 불러올 커밋 수 기본값. 배선 티켓(UND-26)이 필요하면 다르게 준다. */
const val DEFAULT_GRAPH_PAGE_SIZE = 100

/**
 * 커밋 그래프 화면의 상태 홀더 — 페이징 커서, 페이지 경계 carry, 로딩·실패 상태, 행 선택을 소유한다.
 *
 * **Gateway 를 직접 잡지 않는다.** 이력은 [LoadCommitHistoryUseCase] 라는 application 경계로만
 * 읽는다 (architecture-layers 규칙 3, wave 3 결정 A1). JGit 작업을 직접 실행하지도 않는다 —
 * I/O 디스패치와 `Repository` 직렬화는 `HistoryGatewayImpl` 안의 `GitAccess` 몫이다 (wave 2 결정 C1).
 *
 * 레인 배치는 **페이지를 받은 시점에 한 번** 계산해 [rows] 에 담는다. 컴포지션에서 다시 계산하지
 * 않으므로 스크롤 중 배치 비용이 들지 않는다 (compose-ui 규칙 4).
 *
 * 셸 연결(선택한 커밋을 상세 패널로 넘기는 등)은 UND-26 이 한다 — 이 홀더는 상세 패널을 모른다.
 */
@Stable
class GraphViewState(
    private val loadCommitHistory: LoadCommitHistoryUseCase,
    private val refs: List<RefName>,
    private val pageSize: Int = DEFAULT_GRAPH_PAGE_SIZE,
) {
    private var rowsState by mutableStateOf<List<GraphRowItem>>(emptyList())
    private var statusState by mutableStateOf<GraphLoadStatus>(GraphLoadStatus.Idle)
    private var selectedState by mutableStateOf<CommitId?>(null)

    /** 다음 페이지에 넘길 직전 페이지의 레인 상태. 넘기지 않으면 경계에서 통과선이 끊긴다. */
    private var carry: LaneCarry? = null

    /** 페이지가 덜 찬 응답을 받으면 이력이 끝난 것이다 — 그 뒤로는 Gateway 를 부르지 않는다. */
    private var reachedEnd = false

    /** 요청 하나만 진행하도록 막는다. 하단 도달이 연달아 감지돼도 같은 offset 을 두 번 부르지 않는다. */
    private var inFlight = false

    val rows: List<GraphRowItem> get() = rowsState

    val status: GraphLoadStatus get() = statusState

    /** 지금까지 쌓인 행 중 가장 넓은 레인 수. 그래프 열 폭이 행마다 흔들리지 않게 전체 기준으로 잡는다. */
    val laneCount: Int get() = rowsState.maxOfOrNull { it.laneSpan } ?: 0

    val selectedCommitId: CommitId? get() = selectedState

    /** 선택된 커밋 원본. 상세 패널은 이 값을 받아 쓰고 따로 조회하지 않는다 (wave 3 결정 A4). */
    val selectedCommit: Commit?
        get() = selectedState?.let { selected -> rowsState.firstOrNull { it.commit.id == selected }?.commit }

    /** 행 선택. `null` 을 넘기면 선택을 비운다. */
    fun selectCommit(commitId: CommitId?) {
        selectedState = commitId
    }

    /**
     * 첫 페이지를 `offset = 0` 으로 요청한다. 이미 요청했거나 진행 중이면 아무것도 하지 않는다.
     *
     * 취소된 뒤에는 상태가 [GraphLoadStatus.Idle] 로 돌아오므로 다시 호출할 수 있다.
     */
    suspend fun loadInitialPage() {
        if (inFlight || statusState != GraphLoadStatus.Idle) return
        loadPage(offset = 0, previousCarry = null, initial = true)
    }

    /**
     * 하단에 도달했을 때 다음 페이지를 요청한다. 직전 페이지의 carry 를 그대로 넘겨
     * 페이지 경계의 레인 통과선이 끊기지 않게 한다.
     *
     * 첫 페이지가 아직 없거나, 실패했거나, 이력이 끝났으면 아무것도 하지 않는다.
     */
    suspend fun loadNextPage() {
        if (inFlight || reachedEnd || statusState != GraphLoadStatus.Loaded) return
        loadPage(offset = rowsState.size, previousCarry = carry, initial = false)
    }

    private suspend fun loadPage(offset: Int, previousCarry: LaneCarry?, initial: Boolean) {
        inFlight = true
        if (initial) statusState = GraphLoadStatus.Loading
        try {
            appendPage(loadCommitHistory.execute(refs, offset, pageSize), previousCarry, initial)
        } catch (cancellation: CancellationException) {
            // 취소를 성공·빈 결과로 바꾸지 않는다 — 상태만 되돌리고 그대로 전파한다
            // (exception-handling 규칙 5).
            if (initial) statusState = GraphLoadStatus.Idle
            throw cancellation
        } catch (failure: UndineException) {
            // 조용한 fallback 금지 — 빈 목록으로 덮지 않고 실패를 그대로 노출한다.
            statusState = GraphLoadStatus.Failed(failure)
        } finally {
            inFlight = false
        }
    }

    private fun appendPage(commits: List<Commit>, previousCarry: LaneCarry?, initial: Boolean) {
        val page = GraphLaneAssigner.assign(commits, previousCarry)
        val items = commits.zip(page.rows) { commit, row -> GraphRowItem(commit = commit, row = row) }
        rowsState = if (initial) items else rowsState + items
        carry = page.carry
        reachedEnd = commits.size < pageSize
        statusState = GraphLoadStatus.Loaded
    }
}

/** 컴포지션 수명 동안 유지되는 그래프 상태. 저장소·참조가 바뀌면 새 홀더가 만들어진다. */
@Composable
fun rememberGraphViewState(
    loadCommitHistory: LoadCommitHistoryUseCase,
    refs: List<RefName>,
    pageSize: Int = DEFAULT_GRAPH_PAGE_SIZE,
): GraphViewState = remember(loadCommitHistory, refs, pageSize) {
    GraphViewState(loadCommitHistory = loadCommitHistory, refs = refs, pageSize = pageSize)
}
