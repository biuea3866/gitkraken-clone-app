package dev.undine.presentation.graph

import androidx.compose.runtime.Immutable

/**
 * 한 행을 그리는 데 필요한 값 묶음. 행마다 칩 색인을 다시 훑거나 시각 문자열을 다시 만들지 않도록
 * `remember` 로 캐시해 넘긴다 (compose-ui 규칙 4).
 *
 * @property relativeTime 미리 만든 상대 시각 문자열. 기준 시각을 밖에서 주입해야 표시가 결정적이다.
 */
@Immutable
data class GraphRowDisplay(
    val item: GraphRowItem,
    val chips: List<GraphRefChip>,
    val relativeTime: String,
)
