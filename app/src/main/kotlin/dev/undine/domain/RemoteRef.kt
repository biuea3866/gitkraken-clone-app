package dev.undine.domain

/** 원격 참조 1건. [remote] 는 원격 이름(`origin`)이다. */
data class RemoteRef(
    val remote: String,
    val name: RefName,
    val target: CommitId,
)
