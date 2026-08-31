package dev.undine.domain

/**
 * 커밋 생성 결과.
 *
 * 원격 포함 여부는 여기 없다 — 그 값은 커밋 **전에** 판단해야 의미가 있으므로 [AmendPreflight] 가 준다.
 *
 * [previousHead] 와 [baseline] 은 **커밋과 같은 임계 구역에서** 캡처한다. 되돌리기를 기록하는
 * 호출자가 커밋이 끝난 뒤 스스로 읽으면 그 읽기와 커밋 사이에 앱 내부의 다른 조작이 끼어들어,
 * "내 변경 직후" 가 아닌 상태로 기록된다 (UND-73).
 */
data class CommitResult(
    val commitId: CommitId,
    /**
     * 커밋 **직전** HEAD. 되돌리기는 이 지점으로 soft reset 한다 — 새 커밋이면 부모, amend 면
     * 고치기 전 원본 커밋이다(원본은 백업 ref 로 살아 있다).
     *
     * 커밋이 하나도 없던 저장소의 첫 커밋은 되돌릴 지점이 없어 null 이다.
     */
    val previousHead: CommitId?,
    /** 커밋 직후 기준 상태. 되돌리기 직전의 외부 변경 비교에 쓴다. */
    val baseline: RepositoryBaseline,
)
