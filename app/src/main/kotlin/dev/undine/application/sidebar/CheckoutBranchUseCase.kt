package dev.undine.application.sidebar

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStrategy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 옮기기 전이 브랜치가 아니어서 다시 체크아웃할 이름이 없을 때의 사유. */
private const val NO_PREVIOUS_REF_REASON =
    "체크아웃 전 위치가 브랜치가 아니어서 되돌릴 수 없습니다 — reflog 에서 이전 위치를 찾으세요."

/**
 * 체크아웃 실행 결과.
 *
 * @property undoRecordFailure null 이 아니면 **체크아웃은 성공했고 Undo 항목만 남지 않았다**
 *   (`.agent/rules/exception-handling.md` 규칙 8).
 */
data class CheckoutOutcome(val undoRecordFailure: UndineException?)

/**
 * 사이드바에서 고른 참조로 체크아웃하고 되돌리기를 기록한다.
 *
 * **강제 체크아웃은 하지 않는다** — 워킹트리에 커밋되지 않은 변경이 있으면 덮어쓰지 않고
 * `DirtyWorkingTree` 로 실패해 화면이 사용자에게 알린다.
 *
 * 되돌릴 이전 참조는 **Gateway 결과가 준 값**을 쓴다 (UND-73) — 체크아웃 뒤에는 이전 위치를 알 수
 * 없고, 전에 읽으면 그 읽기와 체크아웃 사이의 다른 이동을 놓친다. 체크아웃과 기록은 한
 * [NonCancellable] 단위이며, 묶기 전에 호출자의 취소를 확인한다 (결정 A-L2).
 */
class CheckoutBranchUseCase(
    private val refGateway: RefGateway,
    private val operationRecorder: OperationRecorder,
) {

    suspend operator fun invoke(ref: RefName): CheckoutOutcome {
        // 취소는 **변경 전에만** 관측한다 — 이 뒤로는 체크아웃과 기록이 한 단위라 끊기지 않는다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = refGateway.checkout(ref, force = false)
                val failure = operationRecorder.recordQuietly(GitOperationKind.CHECKOUT) {
                    val previousRef = result.previousRef
                    if (previousRef == null) {
                        operationRecorder.recordIrreversible(
                            GitOperationKind.CHECKOUT,
                            NO_PREVIOUS_REF_REASON,
                            ref.value,
                        )
                    } else {
                        operationRecorder.record(
                            GitOperationKind.CHECKOUT,
                            UndoStrategy.CheckoutRef(previousRef),
                            result.baseline,
                            ref.value,
                        )
                    }
                }
                CheckoutOutcome(failure)
            }
        }
    }
}
