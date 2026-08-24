package dev.undine.domain.cherrypick

/**
 * 중단이 **충돌 해결 중 편집을 지운다**는 사실을 사용자가 확인했다는 증거.
 *
 * Boolean 파라미터가 아니라 타입인 이유는 [CherryPickService.abort] 호출부가 확인 절차를 건너뛸 수
 * 없게 하려는 것이다 — 기본값도 없고 다른 값으로 채울 수도 없으므로 확인 없는 중단은 **컴파일되지
 * 않는다**.
 *
 * [discardedPaths] 는 화면이 사용자에게 무엇이 사라지는지 보여 준 목록이다. [CherryPickService.abort]
 * 는 이 목록이 지금 사라질 편집을 다 담고 있는지 대조하고, 확인 뒤에 편집이 더 생겼으면 중단하지
 * 않는다(낡은 확인).
 *
 * **`domain/merge` 의 `AbortConfirmation` 을 쓰지 않는다.** 하위 패키지끼리의 교차 참조가 금지돼
 * 있기 때문이다(`architecture-layers`). 세 번째 소비자가 생기면 그때 공통 타입을 `domain` 루트로
 * 올리는 것이 그 규칙이 정한 처방이다 — 두 곳뿐인 지금은 각 컨텍스트가 자기 확인을 갖는다.
 *
 * **되돌릴 수 없다.** 중단은 시작 전 커밋까지는 되찾지만, 충돌을 해결하며 워킹트리에 쓴 편집은
 * 어디에도 남지 않는다.
 */
class CherryPickAbortConfirmation private constructor(val discardedPaths: List<String>) {

    companion object {
        /** 화면이 [discardedPaths] 를 보여 주고 사용자 확인을 받은 뒤에만 호출한다. */
        fun ofDiscardedPaths(discardedPaths: List<String>): CherryPickAbortConfirmation =
            CherryPickAbortConfirmation(discardedPaths.toList())
    }
}
