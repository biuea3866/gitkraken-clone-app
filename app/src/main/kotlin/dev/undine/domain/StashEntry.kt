package dev.undine.domain

import java.time.Instant

/** stash 항목 1건. [index] 는 `stash@{N}` 의 N 이다. */
data class StashEntry(
    val index: Int,
    val message: String,
    val target: CommitId,
    val createdAt: Instant,

    /**
     * 추적되지 않는 파일까지 포함해 만든 stash 인지. true 면 pop 하지 않는 한
     * 그 파일들이 워킹트리에서 사라진 상태다 — 화면이 이 사실을 알려야 한다.
     */
    val includedUntracked: Boolean,
)
