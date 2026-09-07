package dev.undine.presentation.graph

import dev.undine.domain.CommitId

/** 화면 테스트가 그래프의 각 부분을 집는 태그. 구조를 바꾸면 이 값도 함께 바뀐다. */
object GraphTags {
    const val ROOT = "graph.root"
    const val LIST = "graph.list"
    const val EMPTY = "graph.empty"
    const val ERROR = "graph.error"
    const val LOADING = "graph.loading"

    /** 표시 폭 밖으로 잘린 레인이 있다는 열 수준 안내. */
    const val LANES_HIDDEN = "graph.lanesHidden"

    /** 그래프 열 폭 조절 손잡이. */
    const val WIDTH_SPLITTER = "graph.splitter.width"

    /** 커밋 행 태그의 공통 접두사 — 구성된 행 수를 세는 데 쓴다. */
    const val ROW_PREFIX = "graph.row."

    private const val LANES_PREFIX = "graph.lanes."

    private const val LANE_HIDDEN_PREFIX = "graph.laneHidden."

    fun row(commit: CommitId): String = "$ROW_PREFIX$commit"

    fun lanes(commit: CommitId): String = "$LANES_PREFIX$commit"

    /** 자기 노드가 표시 폭 밖에 있는 행의 표식. */
    fun laneHidden(commit: CommitId): String = "$LANE_HIDDEN_PREFIX$commit"
}
