package dev.undine.application.conflict

import dev.undine.application.merge.recordMerge
import dev.undine.application.merge.recordRebase
import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 충돌 해결로 이어진 연산의 이력 표시값. 어느 대상인지는 시작한 연산이 이미 이력에 남겼다. */
private const val CONTINUED_LABEL = "충돌 해결 후 계속"

/** 지금 충돌한 파일 목록. */
class LoadConflictedFilesUseCase(private val conflictGateway: ConflictGateway) {

    suspend fun execute(): List<ConflictedFile> = conflictGateway.listConflicted()
}

/** 표식이 든 파일 내용. 사용자가 이미 고친 내용을 살리려면 워킹트리를 읽어야 한다. */
class LoadConflictContentUseCase(private val conflictGateway: ConflictGateway) {

    suspend fun execute(path: String): String = conflictGateway.readConflicted(path)
}

/** 해결 결과를 워킹트리와 인덱스에 함께 반영한다. */
class ResolveConflictUseCase(private val conflictGateway: ConflictGateway) {

    suspend fun execute(path: String, content: String) = conflictGateway.resolve(path, content)

    /** 이진 파일은 합칠 수 없어 한쪽을 그대로 채택한다. */
    suspend fun executeBinary(path: String, side: ConflictSide) =
        conflictGateway.resolveBinary(path, side)
}

/**
 * 계속 결과. 화면은 이 값으로 "끝났다" 와 "아직 충돌이 남았다" 를 구분한다.
 *
 * [undoRecordFailure] 는 두 변이가 함께 갖는다 — 화면이 결과 종류로 분기하지 않고 기록 실패를 읽을
 * 수 있어야 하기 때문이다. null 이 아니면 **병합·리베이스는 이어졌고 Undo 항목만 남지 않았다**
 * (`.agent/rules/exception-handling.md` 규칙 8).
 */
sealed interface ContinueOutcome {

    val undoRecordFailure: UndineException?

    /** 병합이 이어졌다. */
    data class Merged(
        val result: MergeResult,
        override val undoRecordFailure: UndineException?,
    ) : ContinueOutcome

    /** 리베이스가 이어졌다. */
    data class Rebased(
        val result: RebaseResult,
        override val undoRecordFailure: UndineException?,
    ) : ContinueOutcome
}

/**
 * 해결한 뒤 상위 병합·리베이스를 이어가고 되돌리기를 기록한다.
 *
 * 무엇을 이어갈지는 **호출부가 아는 저장소 상태**로 가른다 — 상태를 여기서 다시 읽으면 화면이 보고
 * 있는 상태와 어긋날 수 있고, 두 곳이 각자 읽으면 그 사이 값이 달라진다.
 *
 * 되돌리기는 **연산 전체**를 되돌린다 — 결과에 실려 오는 시작 지점이 `ORIG_HEAD`, 즉 병합·리베이스를
 * 시작하기 전 커밋이기 때문이다. 이어가기와 기록은 한 [NonCancellable] 단위다 (결정 A-L2).
 *
 * @throws UndineException.StateViolation 병합·리베이스가 진행 중이 아닐 때
 */
class ContinueAfterResolveUseCase(
    private val mergeService: MergeService,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(state: RepositoryState): ContinueOutcome {
        // 진행 중이 아니면 저장소를 건드리지 않으므로 취소 불가 구간 **밖**에서 거른다.
        if (state != RepositoryState.MERGING && state != RepositoryState.REBASING) {
            throw UndineException.StateViolation("병합·리베이스가 진행 중이 아닙니다")
        }
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 이어가기와 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                if (state == RepositoryState.MERGING) {
                    val result = mergeService.continueMerge()
                    ContinueOutcome.Merged(result, operationRecorder.recordMerge(result, CONTINUED_LABEL))
                } else {
                    val result = mergeService.continueRebase()
                    ContinueOutcome.Rebased(result, operationRecorder.recordRebase(result, CONTINUED_LABEL))
                }
            }
        }
    }
}

/**
 * 진행 중인 병합·리베이스를 되돌린다.
 *
 * **충돌 해결 중 쓴 워킹트리 편집이 사라진다.** 그래서 [AbortConfirmation] 을 필수로 받는다 —
 * 화면이 사라질 경로를 보여 주고 확인받지 않으면 이 호출을 만들 수 없다. 확인 뒤 편집이 더
 * 생겼으면 `MergeService` 가 거부하므로 화면은 갱신된 목록으로 다시 확인받는다.
 */
class AbortConflictedOperationUseCase(private val mergeService: MergeService) {

    suspend fun execute(confirmation: AbortConfirmation) = mergeService.abort(confirmation)
}
