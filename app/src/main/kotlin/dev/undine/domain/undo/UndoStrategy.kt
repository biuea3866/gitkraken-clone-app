package dev.undine.domain.undo

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.StashEntry

/**
 * 한 연산을 되돌리는 방법. **Git 에는 undo 가 없으므로** 연산마다 흉내 내는 방법이 다르고,
 * 그 방법은 연산을 실행한 쪽이 그 시점에만 알 수 있다 (직전 커밋·이전 ref·`ORIG_HEAD`).
 * 그래서 되돌리기 시점에 추론하지 않고 **기록 시점에 함께 남긴다.**
 *
 * [Reversible] 과 [Irreversible] 을 타입으로 갈라 두면 실행 경로가 복구 불가 항목을
 * 조용히 흘려보낼 수 없다 — 실행은 [Reversible] 만 받는다.
 */
sealed interface UndoStrategy {

    /** 실행할 수 있는 되돌리기. */
    sealed interface Reversible : UndoStrategy

    /** 커밋 되돌리기 — 직전 커밋으로 soft reset 해 변경 내용은 남긴다. */
    data class SoftResetTo(val commit: CommitId) : Reversible

    /** 체크아웃 되돌리기 — 이전 ref 를 다시 체크아웃한다. */
    data class CheckoutRef(val ref: RefName) : Reversible

    /** 브랜치 생성 되돌리기 — 만든 브랜치를 지운다. */
    data class DeleteBranch(val branch: RefName) : Reversible

    /**
     * 병합·리베이스·cherry-pick 되돌리기 — 기록해 둔 `ORIG_HEAD` 로 hard reset 한다.
     * 워킹트리를 덮어쓰므로 [OperationEntry.planUndo] 가 깨끗할 때만 허용한다.
     */
    data class HardResetTo(val commit: CommitId) : Reversible

    /**
     * stash 저장 되돌리기 — 저장한 변경을 워킹트리로 되돌린다.
     *
     * 되돌릴 대상을 "가장 최신 stash" 로 두지 않고 **기록 당시의 [stash] 를 그대로 들고 있는다.**
     * stash 는 브랜치도 HEAD 도 움직이지 않아 [OperationEntry.planUndo] 의 기준 상태 비교가
     * 그 사이의 stash 추가를 잡지 못하기 때문이다 — 최신 것을 꺼내면 기록 뒤 밖에서 쌓인
     * 다른 stash 를 워킹트리에 풀고 그것을 지우게 된다.
     */
    data class PopStash(val stash: StashEntry) : Reversible

    /**
     * 되돌릴 수 없는 연산. [reason] 은 화면이 **그대로 보여줄 수 있는 문장**이다 —
     * 이유를 말하지 않고 조용히 넘기면 사용자는 "왜 안 되지" 로 끝난다.
     */
    data class Irreversible(val reason: String) : UndoStrategy
}
