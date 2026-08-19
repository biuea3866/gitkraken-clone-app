package dev.undine.domain

/** 브랜치 1건. [ahead]·[behind] 는 [upstream] 기준 커밋 수이며 upstream 이 없으면 0 이다. */
data class Branch(
    val name: RefName,
    val target: CommitId,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val upstream: RefName?,
    val ahead: Int,
    val behind: Int,
)
