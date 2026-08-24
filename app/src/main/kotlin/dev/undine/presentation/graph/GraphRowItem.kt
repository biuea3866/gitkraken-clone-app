package dev.undine.presentation.graph

import androidx.compose.runtime.Immutable
import dev.undine.domain.Commit
import dev.undine.domain.graph.GraphRow

private const val SHORT_HASH_LENGTH = 7

/**
 * 화면에 놓이는 커밋 한 행 — 커밋 원본([commit])과 레인 배치 결과([row])를 짝지어 들고 있다.
 *
 * 배치는 [dev.undine.domain.graph.GraphLaneAssigner] 가 **페이지를 불러올 때 한 번** 계산하고
 * [GraphViewState] 가 보관한다. 매 프레임 다시 계산하지 않는다 (compose-ui 규칙 4).
 *
 * 파생값은 생성 시점에 한 번 계산한다 — 스크롤 중 행마다 문자열을 다시 자르지 않기 위해서다.
 * `equals` 는 [commit]·[row] 로만 결정되므로 파생값이 동일성을 흐리지 않는다.
 */
@Immutable
data class GraphRowItem(
    val commit: Commit,
    val row: GraphRow,
) {
    /** 커밋 메시지 제목 줄. 본문은 상세 패널(UND-15) 몫이라 여기서는 표시하지 않는다. */
    val summary: String = commit.message.lineSequence().firstOrNull()?.trim().orEmpty()

    /** 짧은 해시 앞 7자. 고정폭 타이포 토큰과 함께 써야 자릿수가 정렬된다. */
    val shortHash: String = commit.id.value.take(SHORT_HASH_LENGTH)

    /**
     * 이 행이 쓰는 가장 오른쪽 레인 + 1. 그래프 열 폭을 정하는 데 쓴다.
     *
     * 페이지 밖 부모(`toLane == LaneEdge.NO_LANE`)는 음수라 최댓값에 영향을 주지 않는다.
     */
    val laneSpan: Int = maxOf(
        row.lane,
        row.passThrough.maxOfOrNull { it.lane } ?: row.lane,
        row.parents.maxOfOrNull { it.toLane } ?: row.lane,
    ) + 1
}
