package dev.undine.domain

/** 태그 1건. lightweight 태그는 [isAnnotated] 가 false 이고 [message]·[tagger] 가 없다. */
data class Tag(
    val name: RefName,
    val target: CommitId,
    val isAnnotated: Boolean,
    val message: String?,
    val tagger: Person?,
)
