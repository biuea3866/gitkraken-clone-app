package dev.undine.domain

/** 커밋의 author·committer, 태그의 tagger 로 쓰이는 git identity. */
data class Person(
    val name: String,
    val email: String,
)
