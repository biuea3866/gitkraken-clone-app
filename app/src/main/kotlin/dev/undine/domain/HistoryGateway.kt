package dev.undine.domain

/** 커밋 히스토리 페이징 조회. [refs] 가 비면 빈 리스트를 반환한다. */
interface HistoryGateway {

    suspend fun load(refs: List<RefName>, offset: Int, limit: Int): List<Commit>
}
