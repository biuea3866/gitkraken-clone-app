package dev.undine.domain.undo

/**
 * Undo 스택에 기록되는 Git 연산의 종류.
 *
 * "무엇을 되돌리는가" 는 [UndoStrategy] 가 갖고, 여기에는 **화면이 사용자에게 무엇을 되돌렸는지
 * 말할 때 쓰는 이름**만 둔다. 되돌릴 수 있는지 여부를 이 enum 으로 판단하지 않는다 —
 * 같은 연산도 상황에 따라 되돌리는 방법이 다르므로 판단 근거는 전략 쪽에 있다.
 */
enum class GitOperationKind(val label: String) {
    COMMIT("커밋"),
    CHECKOUT("체크아웃"),
    BRANCH_CREATE("브랜치 생성"),
    MERGE("병합"),
    REBASE("리베이스"),
    CHERRY_PICK("cherry-pick"),
    STASH_PUSH("stash 저장"),

    /** 원격에 올라간 것은 앱이 되돌리지 못한다. */
    PUSH("push"),

    /** 지운 변경이 어디에도 남지 않는다. */
    HARD_RESET("hard reset"),

    /** 지운 stash 는 되살릴 수 없다. */
    STASH_DROP("stash 삭제"),
}
