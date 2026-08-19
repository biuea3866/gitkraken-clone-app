package dev.undine.domain

import java.time.Instant

/** 커밋 1건. [parents] 가 2개 이상이면 병합 커밋이다. [message] 는 전문(제목 + 본문)이다. */
data class Commit(
    val id: CommitId,
    val parents: List<CommitId>,
    val message: String,
    val author: Person,
    val committer: Person,
    val authoredAt: Instant,
    val committedAt: Instant,
)
