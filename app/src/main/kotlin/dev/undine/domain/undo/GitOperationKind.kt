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

    /*
     * 아래는 UND-63 이 wave 8 소비자(UND-42 그래프 조작 · UND-45 서브모듈·worktree 패널 ·
     * UND-46 복구 화면)를 위해 한 번에 넓힌 자리다. 되돌릴 수 있는지는 여기서 가르지 않는다 —
     * 같은 종류도 상황에 따라 갈리므로 판단은 UndoStrategy 가 한다. 되돌릴 수 없는 연산도
     * 종류로는 기록한다: 되돌릴 수 없다는 사실을 사용자에게 말하려면 이름이 있어야 한다.
     */

    /**
     * 그래프에서 브랜치를 끌어다 놓아 가리키는 커밋을 바꾼다.
     *
     * 이 제스처는 ref 만 옮기는 것이 아니라 **hard reset** 이라 워킹트리의 변경을 덮어쓴다
     * (`GraphOperationUseCases.resetBranch`). 되돌릴 수 있는지와 무엇을 잃었는지는 다른 질문이고,
     * "브랜치 이동" 만으로는 뒤쪽이 감춰지므로 이름이 그 사실까지 말한다 (UND-84).
     */
    BRANCH_MOVE("브랜치 이동(hard reset · 워킹트리 변경 유실)"),

    /** 그래프에서 태그를 끌어다 놓아 가리키는 커밋을 바꾼다. */
    TAG_MOVE("태그 이동"),

    SUBMODULE_INIT("서브모듈 초기화"),
    SUBMODULE_UPDATE("서브모듈 업데이트"),
    WORKTREE_ADD("worktree 추가"),
    WORKTREE_REMOVE("worktree 제거"),

    /** reflog 에서 찾은 지점으로 되돌려 잃어버린 커밋을 되살린다. */
    REFLOG_RESTORE("reflog 복구"),

    /** bisect 세션의 시작·판정·종료. 세션 단위로 한 항목이다. */
    BISECT_SESSION("bisect 세션"),
}
