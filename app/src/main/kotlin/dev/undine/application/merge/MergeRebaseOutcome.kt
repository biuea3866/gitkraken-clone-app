package dev.undine.application.merge

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.UndineException
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.undo.GitOperationKind

/**
 * 병합 실행 결과. domain 의 [MergeResult] 를 그대로 담고 되돌리기 이력의 사정만 덧붙인다 (결정 G30 1).
 *
 * @property undoRecordFailure null 이 아니면 **병합은 성공했고 Undo 항목만 남지 않았다.**
 */
data class MergeOutcome(
    val result: MergeResult,
    val undoRecordFailure: UndineException?,
)

/** 리베이스 실행 결과. [MergeOutcome] 과 같은 이유로 application 에 둔다. */
data class RebaseOutcome(
    val result: RebaseResult,
    val undoRecordFailure: UndineException?,
)

/**
 * 성공한 병합을 `MERGE` 기록으로 남긴다.
 *
 * 되돌리기 재료(브랜치·이전 위치·기대 위치)는 전부 **Gateway 결과가 준 값**이다 — 병합과 같은
 * 임계 구역에서 캡처했으므로 그 사이의 다른 조작이 섞이지 않는다 (UND-73·결정 G5).
 * 충돌·변경 없음은 남길 되돌리기가 없다: 저장소가 진행 중이거나 그대로다.
 */
internal suspend fun OperationRecorder.recordMerge(
    result: MergeResult,
    targetLabel: String,
): UndineException? = when (result) {
    is MergeResult.Succeeded -> recordHardReset(
        operation = GitOperationKind.MERGE,
        previousHead = result.previousHead,
        baseline = result.baseline,
        targetLabel = targetLabel,
    )

    is MergeResult.Conflicted, MergeResult.AlreadyUpToDate -> null
}

/** 성공한 리베이스를 `REBASE` 기록으로 남긴다. [recordMerge] 와 같은 규칙이다. */
internal suspend fun OperationRecorder.recordRebase(
    result: RebaseResult,
    targetLabel: String,
): UndineException? = when (result) {
    is RebaseResult.Succeeded -> recordHardReset(
        operation = GitOperationKind.REBASE,
        previousHead = result.previousHead,
        baseline = result.baseline,
        targetLabel = targetLabel,
    )

    is RebaseResult.Conflicted, RebaseResult.AlreadyUpToDate -> null
}
