package dev.undine.domain

/** 커밋 히스토리 페이징 조회. [refs] 가 비면 구현이 기본 참조 집합을 정한다. */
interface HistoryGateway {

    suspend fun load(refs: List<RefName>, offset: Int, limit: Int): List<Commit>
}
