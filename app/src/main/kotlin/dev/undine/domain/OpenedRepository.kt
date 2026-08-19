package dev.undine.domain

/** 저장소를 연 결과. detached HEAD 면 [currentBranch] 가 없다. */
data class OpenedRepository(
    val state: RepositoryState,
    val currentBranch: RefName?,
)
