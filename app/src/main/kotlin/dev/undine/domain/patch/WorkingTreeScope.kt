package dev.undine.domain.patch

/**
 * 워킹트리·인덱스 중 어디를 내보낼지. Boolean 두 개(`staged`·`unstaged`)로 표현하지 않는다 —
 * 그러면 `false,false` 라는 뜻 없는 조합이 생긴다.
 */
enum class WorkingTreeScope {

    /** 인덱스에 올라간 변경만 (HEAD ↔ 인덱스). */
    STAGED,

    /** 인덱스에 올리지 않은 변경만 (인덱스 ↔ 워킹트리). */
    UNSTAGED,

    /** 스테이징 여부와 무관한 전체 변경 (HEAD ↔ 워킹트리). */
    ALL,
}
