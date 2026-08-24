package dev.undine.presentation.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.undine.application.search.SearchCommitsUseCase
import dev.undine.application.search.SearchProgress
import dev.undine.domain.Commit
import dev.undine.domain.RefName
import dev.undine.domain.search.CommitSearchCriteria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** 강조할 결과가 없다 — 결과가 0건이거나 첫 결과가 아직 도착하지 않은 상태다. */
private const val NO_HIGHLIGHT = -1

/**
 * `YYYY-MM-DD` 만 읽는다 — 화면이 보여주는 형식 힌트(`search.dateFormatHint`)와 같은 형식이다.
 *
 * 읽지 못한 입력은 `null` 이다. 타이핑 도중의 문자열("2026-0")을 오류로 던지면 입력이 불가능해지므로,
 * 그 축을 조건에서 빼고 화면이 [SearchState.isInvalid] 로 알린다.
 */
private fun parseSearchDate(raw: String): LocalDate? =
    try {
        LocalDate.parse(raw.trim())
    } catch (invalidFormat: DateTimeParseException) {
        null
    }

private fun Map<SearchField, String>.textOf(field: SearchField): String = this[field]?.trim() ?: ""

/**
 * 입력 축을 검색 조건으로 옮긴다. 상태를 건드리지 않는 순수 변환이라 조건 조립 규칙만 따로 읽힌다.
 *
 * 날짜로 읽지 못한 축은 조건에서 빠진다 — 입력 도중의 문자열 때문에 검색이 막히면 안 된다.
 */
private fun criteriaOf(queries: Map<SearchField, String>, zone: ZoneId): CommitSearchCriteria =
    CommitSearchCriteria(
        message = queries.textOf(SearchField.MESSAGE),
        author = queries.textOf(SearchField.AUTHOR),
        hashPrefix = queries.textOf(SearchField.HASH),
        filePath = queries.textOf(SearchField.PATH),
        since = parseSearchDate(queries.textOf(SearchField.SINCE)),
        until = parseSearchDate(queries.textOf(SearchField.UNTIL)),
        zone = zone,
    )

/**
 * 검색 화면 상태 홀더 — 입력 축을 조건으로 묶고, 페이지 순회 결과를 점진적으로 모은다.
 *
 * 조건은 전부 AND 로 결합하며 판정 규칙 자체는 [CommitSearchCriteria] 소유다. 이 홀더는 **언제 검색을
 * 시작하고 언제 취소하는가**만 정한다. Gateway·JGit 을 알지 못하고 [SearchCommitsUseCase] 만 호출한다
 * (architecture-layers).
 *
 * 입력이 바뀌어 조건이 달라지면 진행 중이던 순회를 취소하고 새로 시작한다. 취소만으로는 부족하다 —
 * 이미 디스패치된 늦은 결과가 새 결과 뒤에 붙을 수 있어, 시작 시점의 세대 번호가 맞을 때만 결과를
 * 반영한다.
 *
 * @param scope 화면 수명에 묶인 스코프. [rememberSearchState] 가 주는 컴포지션 스코프가 표준이며,
 *   컴포지션이 사라질 때 진행 중인 순회도 함께 취소된다 (kotlin-idioms 9).
 * @param refs 검색 대상 참조. 현재 브랜치 HEAD 하나로 시작한다 (wave 3 결정 A4). 비면 결과가 0건이다.
 * @param zone 날짜 축을 커밋 시각과 견줄 때 쓰는 표준시.
 */
@Stable
class SearchState(
    private val searchCommits: SearchCommitsUseCase,
    private val scope: CoroutineScope,
    private val refs: List<RefName>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val queries = mutableStateMapOf<SearchField, String>()
    private val matches = mutableStateListOf<Commit>()

    private var phaseState: SearchPhase by mutableStateOf(SearchPhase.Idle)
    private var highlightIndex by mutableStateOf(NO_HIGHLIGHT)
    private var scannedCommits by mutableStateOf(0)
    private var estimatedTotalCommits by mutableStateOf(0)

    private var searchJob: Job? = null
    private var runningCriteria: CommitSearchCriteria? = null

    /** 검색 세대. 취소된 순회의 늦은 결과를 걸러내는 기준이다. */
    private var generation = 0

    val phase: SearchPhase get() = phaseState

    /** 지금까지 찾은 커밋. 순회가 끝나기 전에도 채워진다. */
    val results: List<Commit> get() = matches

    /** 키보드로 이동 중인 결과. 결과가 없으면 `null` 이다. */
    val highlightedCommit: Commit? get() = matches.getOrNull(highlightIndex)

    /** 강조 위치. 목록 렌더링이 선택 표시를 그리는 데 쓴다. */
    val highlightedIndex: Int get() = highlightIndex

    /**
     * 순회 진행률 `0.0~1.0` — 진행 막대가 그리는 값이다.
     *
     * 분모는 [SearchProgress.Scanned.estimatedTotalCommits] 로, 남은 페이지가 하나뿐이라고 가정한
     * 낙관적 추정이다. 전체 커밋 수를 미리 아는 방법이 없으므로 정확한 백분율은 만들 수 없지만, 이 값은
     * 페이지를 넘길수록 단조 증가하고 순회가 끝나기 전에 1.0 이 되지 않는다. 첫 페이지를 훑기 전에는
     * 훑은 양이 없으므로 0.0 이다.
     */
    val scanProgress: Float
        get() = if (estimatedTotalCommits <= 0) 0f else scannedCommits.toFloat() / estimatedTotalCommits

    fun queryOf(field: SearchField): String = queries[field] ?: ""

    /** 축 하나의 입력을 갱신한다. 조건이 실제로 달라졌을 때만 검색을 다시 시작한다. */
    fun updateQuery(field: SearchField, value: String) {
        if (queryOf(field) == value) return
        queries[field] = value
        restartSearch()
    }

    /** 모든 축을 비운다. 조건이 없으면 검색하지 않으므로 결과도 함께 사라진다. */
    fun clearFilters() {
        queries.clear()
        restartSearch()
    }

    /** 날짜 축 입력을 날짜로 읽지 못했다. 그 축은 조건에서 빠진 상태다. */
    fun isInvalid(field: SearchField): Boolean {
        val raw = queryOf(field)
        return field.isDate && raw.isNotBlank() && parseSearchDate(raw) == null
    }

    /** 강조 위치를 [delta] 만큼 옮긴다. 목록 밖으로는 나가지 않는다. */
    fun moveHighlightBy(delta: Int) {
        if (matches.isEmpty()) return
        highlightIndex = (highlightIndex + delta).coerceIn(0, matches.lastIndex)
    }

    /** 목록에서 직접 고른 행을 강조한다. */
    fun highlightAt(index: Int) {
        if (index in matches.indices) highlightIndex = index
    }

    private fun restartSearch() {
        val criteria = criteriaOf(queries, zone)
        if (criteria == runningCriteria) return

        searchJob?.cancel()
        generation += 1
        val startedGeneration = generation
        matches.clear()
        highlightIndex = NO_HIGHLIGHT
        scannedCommits = 0
        estimatedTotalCommits = 0

        if (criteria.isEmpty) {
            // 조건이 하나도 없으면 검색을 시작하지 않는다 (wave 3 결정 §UND-20).
            searchJob = null
            runningCriteria = null
            phaseState = SearchPhase.Idle
            return
        }

        runningCriteria = criteria
        phaseState = SearchPhase.Running
        searchJob = launchSearch(criteria, startedGeneration)
    }

    /**
     * [CancellationException] 은 새 검색이 시작됐다는 뜻이므로 상태를 건드리지 않고 그대로 올린다
     * (exception-handling 5). 나머지 실패는 원문을 보존해 화면에 알린다 — 예상 못 한 예외도 사용자에게
     * 도달해야 한다 (exception-handling 4).
     */
    @Suppress("TooGenericExceptionCaught", "RethrowCaughtException")
    private fun launchSearch(criteria: CommitSearchCriteria, startedGeneration: Int): Job =
        scope.launch {
            try {
                searchCommits.execute(refs, criteria)
                    .collect { progress -> consume(startedGeneration, progress) }
                settlePhase(startedGeneration, SearchPhase.Completed)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                settlePhase(startedGeneration, SearchPhase.Failed(failure))
            }
        }

    /** 취소된 순회의 늦은 사건은 세대가 어긋나므로 결과에도 진행률에도 반영하지 않는다. */
    private fun consume(startedGeneration: Int, progress: SearchProgress) {
        if (startedGeneration != generation) return
        when (progress) {
            is SearchProgress.Match -> {
                matches.add(progress.commit)
                // 첫 결과가 도착하면 바로 키보드로 열 수 있어야 한다.
                if (highlightIndex == NO_HIGHLIGHT) highlightIndex = 0
            }

            is SearchProgress.Scanned -> {
                scannedCommits = progress.scannedCommits
                estimatedTotalCommits = progress.estimatedTotalCommits
            }
        }
    }

    private fun settlePhase(startedGeneration: Int, settled: SearchPhase) {
        if (startedGeneration != generation) return
        phaseState = settled
    }
}

/**
 * 컴포지션 수명에 묶인 검색 상태.
 *
 * 스코프는 [rememberCoroutineScope] 가 준다 — 컴포지션이 사라지면 진행 중인 순회도 취소된다.
 * UseCase 주입과 대상 참조 결정은 배선(UND-26) 몫이다.
 */
@Composable
fun rememberSearchState(
    searchCommits: SearchCommitsUseCase,
    refs: List<RefName>,
    zone: ZoneId = ZoneId.systemDefault(),
): SearchState {
    val scope = rememberCoroutineScope()
    return remember(searchCommits, refs, zone) { SearchState(searchCommits, scope, refs, zone) }
}
