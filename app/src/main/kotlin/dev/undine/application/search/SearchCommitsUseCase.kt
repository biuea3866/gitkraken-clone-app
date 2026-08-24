package dev.undine.application.search

import dev.undine.domain.Commit
import dev.undine.domain.DiffGateway
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName
import dev.undine.domain.search.CommitSearchCriteria
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 한 번에 읽어 오는 커밋 수. 화면이 결과를 기다리는 시간과 조회 횟수의 절충값이다. */
private const val DEFAULT_PAGE_SIZE = 200

/** 병합 커밋에서 비교할 부모 — 첫 부모다 (wave 3 결정 §UND-20). */
private const val FIRST_PARENT_INDEX = 0

/**
 * 조건에 맞는 커밋을 **찾는 대로 흘려보내는** 검색.
 *
 * 전체 이력을 다 훑은 뒤 결과를 주면 수만 커밋 저장소에서 화면이 멈춘 것처럼 보인다. 그래서
 * [HistoryGateway] 의 offset/limit 페이지를 순서대로 요청하면서 매칭되는 커밋을 즉시 방출하고,
 * 페이지를 넘길 때마다 훑은 양([SearchProgress.Scanned])을 함께 알린다.
 * 수집을 멈추면(화면이 검색을 취소하면) 다음 페이지를 조회하지 않는다 — [Flow] 의 취소가
 * 그대로 순회 중단이다. 긴 순회 중에도 [ensureActive] 로 취소를 확인한다.
 *
 * 판정 규칙은 [CommitSearchCriteria] 소유다. 이 UseCase 는 **조회 순서만** 엮는다 —
 * 값싼 메타데이터 조건으로 후보를 좁힌 뒤에만 커밋마다 diff 를 계산하는 경로 필터를 적용한다.
 *
 * 디스패처를 다시 지정하지 않는다. Git I/O 를 `Dispatchers.IO` 로 넘기는 것은 Gateway 구현이
 * 공유하는 `GitAccess` 의 책임이라, 여기서 한 번 더 감싸면 경계가 둘로 갈린다.
 *
 * 실패는 감싸지 않고 그대로 올린다 — 조회 실패를 빈 결과로 바꾸면 화면이 "결과 0건" 으로 오해한다.
 */
class SearchCommitsUseCase(
    private val historyGateway: HistoryGateway,
    private val diffGateway: DiffGateway,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {

    /**
     * [refs] 에서 도달 가능한 커밋을 위상 정렬 순서로 훑어 [criteria] 에 맞는 커밋을 방출한다.
     *
     * [refs] 가 비면 조회할 이력이 없어 결과 없이 끝난다 — 커밋이 하나도 없는 저장소가 이 경우다.
     */
    fun execute(refs: List<RefName>, criteria: CommitSearchCriteria): Flow<SearchProgress> = flow {
        var scanned = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = historyGateway.load(refs, scanned, pageSize)
            if (page.isEmpty()) return@flow

            page.filter(criteria::matchesMetadata)
                .forEach { commit ->
                    if (touchesFilteredPath(commit, criteria)) emit(SearchProgress.Match(commit))
                }

            scanned += page.size
            // 채워지지 않은 페이지가 마지막이다 — 끝났다는 사실은 흐름의 종료가 알린다.
            if (page.size < pageSize) return@flow
            emit(SearchProgress.Scanned(scanned, scanned + pageSize))
        }
    }

    private suspend fun touchesFilteredPath(commit: Commit, criteria: CommitSearchCriteria): Boolean =
        !criteria.requiresFileChanges ||
            criteria.matchesChangedFiles(diffGateway.changedFiles(commit.id, FIRST_PARENT_INDEX))
}
