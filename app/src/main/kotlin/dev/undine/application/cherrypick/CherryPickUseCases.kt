package dev.undine.application.cherrypick

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickAbortConfirmation
import dev.undine.domain.cherrypick.CherryPickResult
import dev.undine.domain.cherrypick.CherryPickService
import dev.undine.domain.undo.GitOperationKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * cherry-pick 실행 결과. domain 결과를 그대로 담고 되돌리기 이력의 사정만 덧붙인다 (결정 G30 1).
 *
 * @property undoRecordFailure null 이 아니면 **적용은 성공했고 Undo 항목만 남지 않았다.**
 */
data class CherryPickOutcome(
    val result: CherryPickResult,
    val undoRecordFailure: UndineException?,
)

/**
 * 고른 커밋의 변경만 현재 브랜치로 가져오고 되돌리기를 기록한다.
 *
 * UseCase 는 얇다 — 적용 순서·워킹트리 검사 같은 판단은 [CherryPickService] 에 있고 여기서는 순서만
 * 엮는다. 결과를 바꾸거나 삼키지 않고 그대로 올린다: 충돌과 "이미 적용됨" 은 [CherryPickResult] 로,
 * 시작 전 실패는 예외로 presentation 까지 간다.
 *
 * 되돌리기는 **끊기지 않고 적용된 커밋 묶음**을 한 항목으로 되돌린다 — 그 안에서 반쯤 되돌리면
 * 사용자가 어디까지 남았는지 알 수 없다. 그 시작점과 기대 위치는 결과가 실어 준 값이다 (UND-73).
 * 충돌로 멈춰도 **이미 만들어진 커밋은 그 자리에서 기록한다** — 중단(abort)은 마지막 단계의
 * 시작점까지만 되감아 앞선 커밋을 남기므로, 여기서 기록하지 않으면 되돌릴 방법이 사라진다.
 * 이어가기([ContinueCherryPickUseCase])는 그 뒤에 만든 커밋을 자기 항목으로 남기고, 두 항목을
 * 차례로 되돌리면 시작 전으로 돌아간다.
 *
 * 적용과 기록은 한 [NonCancellable] 단위다 (결정 A-L2). 화면 배선은 아직 없다.
 *
 * @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있어 시작하지 않았을 때
 */
class CherryPickCommitsUseCase(
    private val cherryPickService: CherryPickService,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(commits: List<CommitId>, recordOrigin: Boolean = false): CherryPickOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 적용과 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = cherryPickService.cherryPick(commits, recordOrigin)
                CherryPickOutcome(result, operationRecorder.recordCherryPick(result))
            }
        }
    }
}

/**
 * 만들어진 cherry-pick 커밋을 `CHERRY_PICK` 기록으로 남긴다.
 *
 * **충돌도 기록 대상이다** — 멈추기 전에 만든 커밋이 있으면 그 묶음은 저장소에 남아 있다.
 * 아무것도 만들지 않은 충돌과 "이미 적용됨" 만 남길 되돌리기가 없다.
 */
private suspend fun OperationRecorder.recordCherryPick(result: CherryPickResult): UndineException? =
    when (result) {
        is CherryPickResult.Applied ->
            recordPicked(result.created, result.previousHead, result.baseline)

        // baseline 이 있다는 것은 멈추기 전에 만든 커밋이 있다는 뜻이다 (CherryPickResult.Conflicted 계약).
        is CherryPickResult.Conflicted -> result.baseline?.let { baseline ->
            recordPicked(result.created, result.previousHead, baseline)
        }

        CherryPickResult.AlreadyApplied -> null
    }

private suspend fun OperationRecorder.recordPicked(
    created: List<CommitId>,
    previousHead: CommitId?,
    baseline: RepositoryBaseline,
): UndineException? = recordHardReset(
    operation = GitOperationKind.CHERRY_PICK,
    previousHead = previousHead,
    baseline = baseline,
    targetLabel = created.joinToString(separator = ", ") { it.value },
)

/**
 * 충돌을 해결한 뒤 멈춘 커밋을 마무리하고 되돌리기를 기록한다.
 *
 * 이어간 커밋은 **자기 항목**으로 남는다 — 멈추기 전 묶음은 [CherryPickCommitsUseCase] 가 이미
 * 기록했고, 그 시작점은 이어가기 시점에는 알 수 없다(단계마다 `ORIG_HEAD` 가 갱신된다). 두 항목을
 * 차례로 되돌리면 cherry-pick 시작 전으로 돌아간다.
 *
 * 이어가기와 기록은 한 [NonCancellable] 단위다 (결정 A-L2).
 *
 * @throws UndineException.StateViolation cherry-pick 이 진행 중이 아닐 때
 */
class ContinueCherryPickUseCase(
    private val cherryPickService: CherryPickService,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(): CherryPickOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 이어가기와 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = cherryPickService.continueAfterResolve()
                CherryPickOutcome(result, operationRecorder.recordCherryPick(result))
            }
        }
    }
}

/**
 * 진행 중인 cherry-pick 을 되돌린다.
 *
 * **충돌 해결 중 쓴 워킹트리 편집이 사라진다.** 그래서 [CherryPickAbortConfirmation] 을 필수로 받는다 —
 * 화면이 사라질 경로를 보여 주고 확인받지 않으면 이 호출을 만들 수 없다.
 */
class AbortCherryPickUseCase(private val cherryPickService: CherryPickService) {

    suspend fun execute(confirmation: CherryPickAbortConfirmation) = cherryPickService.abort(confirmation)
}
