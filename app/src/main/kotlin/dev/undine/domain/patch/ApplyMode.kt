package dev.undine.domain.patch

import dev.undine.domain.Person

/**
 * 패치를 어디까지 반영할지. Boolean 조합이 아니라 **세 변이**로 나눈다 —
 * 커밋 메타데이터는 [CreateCommit] 에만 실려, 다른 모드에서 의미 없는 인자가 생기지 않는다.
 */
sealed interface ApplyMode {

    /** 워킹트리에만 반영한다. 스테이징은 사용자가 검토 후 직접 한다. */
    data object WorkingTreeOnly : ApplyMode

    /** 워킹트리와 인덱스에 반영한다. 바로 커밋할 수 있는 상태가 된다. */
    data object Index : ApplyMode

    /**
     * 워킹트리·인덱스에 반영하고 커밋까지 만든다.
     *
     * **패치에 담긴 메타데이터가 우선**이다 (`git format-patch` 의 `From:`·`Subject:`).
     * 메타데이터가 없는 일반 diff 일 때만 여기 값을 쓴다. 둘 다 없으면 적용을 거부한다 —
     * 작성자와 메시지를 지어내지 않는다.
     */
    data class CreateCommit(val author: Person?, val message: String?) : ApplyMode
}
