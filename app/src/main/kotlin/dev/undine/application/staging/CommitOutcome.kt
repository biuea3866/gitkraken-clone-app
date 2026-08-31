package dev.undine.application.staging

import dev.undine.domain.CommitResult
import dev.undine.domain.UndineException

/**
 * 커밋·amend 실행 결과. domain 의 [CommitResult] 를 그대로 담고, 되돌리기 이력의 사정만 덧붙인다.
 *
 * 이 타입이 application 에 있는 이유는 [undoRecordFailure] 가 **Git 이 돌려준 사실이 아니기**
 * 때문이다 — 되돌리기 이력은 이 앱의 사정이므로 domain 결과에 섞지 않는다 (결정 G30 1).
 *
 * @property undoRecordFailure null 이 아니면 **커밋은 성공했고 Undo 항목만 남지 않았다.** 기록 실패를
 *   커밋 실패로 승격하면 화면은 "실패" 를 보여 주는데 저장소에는 커밋이 있고, 로그로만 삼키면
 *   되돌릴 수 없게 된 사실이 사용자에게 닿지 않는다 (`.agent/rules/exception-handling.md` 규칙 8).
 */
data class CommitOutcome(
    val result: CommitResult,
    val undoRecordFailure: UndineException?,
)
