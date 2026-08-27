package dev.undine.domain

/**
 * 되돌리기의 **기준 상태** — 기록 시점에 어느 브랜치의 어느 커밋 위에 있었는가.
 *
 * 되돌리기 직전 지금 상태와 비교해, 그 사이 저장소가 바뀌었으면 되돌리지 않는다. 비교 없이
 * 실행하면 사용자가 의도하지 않은 커밋으로 reset 된다.
 *
 * **변경 연산의 결과가 이 값을 실어 준다.** 기록하는 쪽이 변경이 끝난 뒤 따로 읽으면 그 읽기가
 * 변경과 다른 임계 구역이라, 그 사이에 앱 내부의 다른 조작이 끼어들면 "내 변경 직후" 가 아니라
 * 남의 변경까지 반영된 상태가 기록된다 (UND-73). 그래서 각 변경 계약이 자기 임계 구역 안에서
 * 캡처해 결과로 돌려준다 — `previousTarget` 이 같은 이유로 결과에 실리는 것과 같다 (UND-72).
 *
 * [branch] 가 null 이면 브랜치 위가 아니다 — detached HEAD 이거나 커밋이 하나도 없는 저장소다.
 * 되돌리기는 브랜치 위에서만 허용하므로 이 값이 그 판단의 근거가 된다.
 *
 * `domain` 루트에 둔다. 하위 패키지(`domain/undo`·`domain/reflog`)와 루트의 Gateway 계약이 함께
 * 쓰는 공통 타입이고, 하위 패키지끼리의 교차 참조는 금지되기 때문이다.
 */
data class RepositoryBaseline(
    val branch: RefName?,
    val head: CommitId?,
) {
    val isOnBranch: Boolean
        get() = branch != null
}
