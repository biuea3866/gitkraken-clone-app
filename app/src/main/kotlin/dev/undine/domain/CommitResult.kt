package dev.undine.domain

/**
 * 커밋 생성 결과.
 *
 * 원격 포함 여부는 여기 없다 — 그 값은 커밋 **전에** 판단해야 의미가 있으므로 [AmendPreflight] 가 준다.
 */
data class CommitResult(
    val commitId: CommitId,
)
