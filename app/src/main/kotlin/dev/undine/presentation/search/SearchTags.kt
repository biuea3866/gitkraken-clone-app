package dev.undine.presentation.search

import dev.undine.domain.CommitId

/** 화면 테스트가 검색 패널의 각 영역을 집는 태그. 영역 경계를 바꾸면 이 값도 함께 바뀐다. */
object SearchTags {
    const val ROOT = "search.root"
    const val CLEAR = "search.clear"
    const val SEARCHING = "search.searching"
    const val PROGRESS = "search.progress"
    const val FOUND_COUNT = "search.foundCount"
    const val FAILED = "search.failed"
    const val IDLE_STATE = "search.idleState"
    const val EMPTY_STATE = "search.emptyState"
    const val RESULT_LIST = "search.resultList"
    const val INVALID_DATE = "search.invalidDate"

    /** 입력칸 하나. 축 이름을 붙여 여섯 칸을 구분한다. */
    fun field(field: SearchField): String = "search.field.${field.name.lowercase()}"

    /** 결과 행. 커밋 해시를 붙여 어떤 커밋의 행인지 구분한다. */
    fun row(commit: CommitId): String = "search.row.${commit.value}"
}
