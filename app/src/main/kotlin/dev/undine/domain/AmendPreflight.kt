package dev.undine.domain

/**
 * amend 를 실행하기 전에 조회한 대상 커밋과 그 커밋의 원격 포함 여부.
 *
 * 원격 포함 여부가 **커밋 결과가 아니라 여기** 있는 이유: 이미 push 된 커밋을 고쳐 쓸지는
 * HEAD 를 다시 쓰기 **전에** 물어야 하는 질문이다. 커밋한 뒤에 경고해 봤자 되돌릴 결정이 없다.
 *
 * [existsOnRemote] 판정 범위는 현재 브랜치 업스트림의 remote-tracking ref 하나다.
 * 업스트림이 없으면 "모른다" 가 아니라 원격 미포함으로 본다.
 */
data class AmendPreflight(
    val target: CommitId,
    val existsOnRemote: Boolean,
)
