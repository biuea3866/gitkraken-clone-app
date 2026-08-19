package dev.undine.domain

/**
 * 커밋 생성 결과. [existsOnRemote] 는 amend 로 이미 push 된 커밋을 고쳐 쓰는 상황을
 * UI 가 경고할 수 있게 하기 위한 값이다.
 */
data class CommitResult(
    val commitId: CommitId,
    val existsOnRemote: Boolean,
)
