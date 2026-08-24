package dev.undine.domain.rebase

/**
 * 리베이스 계획에서 커밋 하나에 지정하는 동작. `git rebase -i` 의 todo 키워드에 대응한다.
 *
 * enum 이 아니라 sealed 인 이유는 [Reword] 가 **새 메시지를 함께 들어야** 하기 때문이다 — JGit
 * `InteractiveHandler` 는 메시지를 실행 중 콜백으로 요구하므로, 계획이 그 값을 미리 담지 않으면
 * 실행 중에 사용자에게 다시 물어야 하고 그러면 "적용 전까지 저장소 무변경" 이 깨진다.
 */
sealed interface RebaseAction {

    /** 그대로 적용한다. */
    data object Pick : RebaseAction

    /** 내용은 그대로, 메시지만 [message] 로 바꾼다. */
    data class Reword(val message: String) : RebaseAction

    /** 적용한 뒤 멈춘다 — 사용자가 워킹트리를 고치고 계속한다. */
    data object Edit : RebaseAction

    /** 앞 커밋에 합치고 두 메시지를 모두 남긴다. */
    data object Squash : RebaseAction

    /** 앞 커밋에 합치고 이 커밋의 메시지는 버린다. */
    data object Fixup : RebaseAction

    /** 결과 이력에서 뺀다. */
    data object Drop : RebaseAction
}

/** 앞 커밋에 흡수되는 동작인지 — 미리보기에서 묶어 보여줄 기준이다. */
val RebaseAction.absorbsIntoPrevious: Boolean
    get() = this is RebaseAction.Squash || this is RebaseAction.Fixup

/**
 * 실행 중 저장소가 멈추는 동작인지.
 *
 * 사용자가 예상하지 못하면 멈춘 화면을 오류로 오해하므로 계획 화면이 미리 표시한다.
 * [RebaseAction.Reword] 는 메시지를 계획에 이미 담고 있어 멈추지 않는다.
 */
val RebaseAction.stopsDuringRun: Boolean
    get() = this is RebaseAction.Edit
