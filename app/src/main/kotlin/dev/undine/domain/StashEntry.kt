package dev.undine.domain

import java.time.Instant

/** stash 항목 1건. [index] 는 `stash@{N}` 의 N 이다. */
data class StashEntry(
    val index: Int,
    val message: String,
    val target: CommitId,
    val createdAt: Instant,
)
