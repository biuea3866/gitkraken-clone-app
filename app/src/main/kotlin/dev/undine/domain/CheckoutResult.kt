package dev.undine.domain

/**
 * 체크아웃 결과.
 *
 * 체크아웃은 "어디에 있었는가" 를 남기지 않으면 되돌릴 수 없다 — 옮기고 나면 이전 위치를 알 방법이
 * 없다. 그래서 [RefGateway.checkout] 이 **옮기기와 같은 임계 구역에서** 이전 위치를 캡처해 준다.
 * 호출자가 체크아웃 전에 따로 읽으면 그 읽기와 체크아웃 사이의 다른 이동을 놓친다 (UND-73).
 *
 * [previousRef] 가 null 이면 옮기기 전이 브랜치가 아니었다는 뜻이다 — detached HEAD 이거나 커밋이
 * 하나도 없는 저장소다. 그 경우 다시 체크아웃할 이름이 없어 되돌리기를 기록할 수 없다.
 */
data class CheckoutResult(
    val previousRef: RefName?,
    /** 체크아웃 직후 기준 상태. 되돌리기 직전의 외부 변경 비교에 쓴다. */
    val baseline: RepositoryBaseline,
)
