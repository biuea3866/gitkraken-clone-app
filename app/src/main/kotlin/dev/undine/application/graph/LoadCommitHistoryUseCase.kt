package dev.undine.application.graph

import dev.undine.domain.Commit
import dev.undine.domain.HistoryGateway
import dev.undine.domain.RefName

/**
 * 커밋 이력 한 페이지를 읽는다 — 그래프 화면이 Gateway 를 직접 잡지 않게 하는 application 경계다
 * (architecture-layers 규칙 3, wave 3 결정 A1).
 *
 * **얇게 둔다.** Gateway 호출과 결과 전달만 하고 분기·검증·상태 전이는 하지 않는다.
 * 레인 배치는 `GraphLaneAssigner`(domain), 페이징 커서와 실패 표현은 `GraphViewState`(presentation)가
 * 각각 소유한다.
 *
 * I/O 디스패치와 `Repository` 직렬화는 `HistoryGatewayImpl` 안의 `GitAccess` 가 맡으므로
 * 여기서 `withContext` 를 다시 걸지 않는다 (wave 2 결정 C1). 취소는 그대로 전파된다.
 */
class LoadCommitHistoryUseCase(
    private val historyGateway: HistoryGateway,
) {
    suspend fun execute(refs: List<RefName>, offset: Int, limit: Int): List<Commit> =
        historyGateway.load(refs, offset, limit)
}
