package dev.undine.domain.merge

import dev.undine.domain.CommitId

/**
 * 건너뛰기가 **지금 적용 중인 커밋을 결과 이력에서 지운다**는 사실을 사용자가 확인했다는 증거.
 *
 * Boolean 파라미터가 아니라 타입인 이유는 [MergeService.skipRebaseCommit] 호출부가 확인 절차를
 * 건너뛸 수 없게 하려는 것이다 — 기본값도 없고 다른 값으로 채울 수도 없으므로, 확인 없는 건너뛰기는
 * **컴파일되지 않는다**. [AbortConfirmation] 과 같은 이유로 같은 모양을 쓴다.
 *
 * [skippedCommit] 은 화면이 사용자에게 **무엇이 사라지는지 보여 준 커밋**이다 — 어떤 커밋이 사라지는지
 * 모른 채 확인할 수는 없으므로 대상을 함께 받는다. [MergeService.skipRebaseCommit] 은 저장소가 지금
 * 멈춰 있는 커밋과 이 값을 대조하고, 어긋나면 건너뛰지 않는다(낡은 확인).
 *
 * **되돌릴 수 없다.** 건너뛴 커밋의 변경은 결과 이력 어디에도 남지 않는다.
 */
class SkipConfirmation private constructor(val skippedCommit: CommitId) {

    companion object {
        /** 화면이 [skippedCommit] 을 보여 주고 사용자 확인을 받은 뒤에만 호출한다. */
        fun ofSkippedCommit(skippedCommit: CommitId): SkipConfirmation = SkipConfirmation(skippedCommit)
    }
}
