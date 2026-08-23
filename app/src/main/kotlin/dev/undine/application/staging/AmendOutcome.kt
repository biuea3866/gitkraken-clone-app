package dev.undine.application.staging

import dev.undine.domain.CommitId
import dev.undine.domain.CommitResult

/**
 * [AmendCommitUseCase] 실행 결과. 화면은 이 값으로 "고쳤다" 와 "물어봐야 한다" 를 구분한다.
 *
 * sealed 인 이유: 확인이 필요한 상태를 nullable 결과로 표현하면 화면이 그 분기를 조용히 빠뜨린다.
 */
sealed interface AmendOutcome {

    /** amend 가 실행됐다. */
    data class Amended(val result: CommitResult) : AmendOutcome

    /**
     * [target] 이 원격에 이미 있어 실행하지 않았다. 저장소는 그대로다.
     * 사용자가 동의하면 화면은 같은 [target] 으로 [AmendCommitUseCase.confirm] 을 부른다.
     */
    data class ConfirmationRequired(val target: CommitId) : AmendOutcome
}
