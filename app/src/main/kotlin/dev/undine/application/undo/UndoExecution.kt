package dev.undine.application.undo

import dev.undine.domain.UndineException
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome

/**
 * 되돌리기 **요청 한 건이 실제로 어떻게 끝났는가**.
 *
 * [UndoOutcome] 만으로는 부족하다. 그것은 "되돌렸다 / 되돌리지 않았다" 라는 **판단 결과**이지,
 * 요청이 도중에 어긋났거나 실패했다는 사실을 담지 못한다. 그 둘을 한 타입으로 뭉개면 화면이
 * 실패를 성공처럼, 어긋난 대상을 정상처럼 보여주게 된다.
 *
 * 세 갈래를 sealed 로 닫아 두면 상태 홀더가 어느 하나도 조용히 빠뜨릴 수 없다.
 */
sealed interface UndoExecution {

    /** 판단이 끝까지 갔다. [outcome] 이 되돌렸는지 거부했는지를 말한다. */
    data class Completed(val outcome: UndoOutcome) : UndoExecution

    /**
     * 되돌릴 수 없는 기록을 사용자 확인 뒤 이력에서 지웠다. 저장소는 건드리지 않았다.
     *
     * [Completed] 와 나눈 이유는 화면 문구가 다르기 때문이다 — 거부 사유만 다시 보여주면
     * 사용자는 기록이 지워졌는지 알 수 없다.
     */
    data class Discarded(val entry: OperationEntry, val refusal: UndoOutcome.Refused) : UndoExecution

    /**
     * 미리 본 항목이 더 이상 최상단이 아니어서 **아무것도 하지 않았다**.
     *
     * 사용자에게 보여준 대상과 실제 대상이 어긋난 상태로 Git 을 바꾸는 것보다, 아무것도 하지 않고
     * 다시 읽는 편이 항상 낫다. 폐기 경로에서는 "이제 되돌릴 수 있게 됐다" 도 여기로 온다 —
     * 어느 쪽이든 화면이 할 일은 같다: 다시 읽고 사용자에게 현재 대상을 보여준다.
     */
    data object TargetChanged : UndoExecution

    /**
     * 실행 도중 실패했다. [entry] 는 그때 이미 소비된 기록이다.
     *
     * 기록을 스택에 되돌려 놓지 않는다 — stash 되돌리기처럼 **일부만 적용된 뒤** 실패했을 수 있고,
     * 그 상태에서 재실행하면 같은 변경을 두 번 적용한다. 대신 소비된 기록을 실패 결과에 실어
     * 화면이 "무엇이 실패했는지" 를 말할 수 있게 한다 (예외 처리 규칙 6 — 부분 실패를 성공으로
     * 보고하지 않는다).
     */
    data class Failed(val entry: OperationEntry, val cause: UndineException) : UndoExecution
}
