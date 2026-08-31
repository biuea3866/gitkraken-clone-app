package dev.undine.application.toolbar

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.Progress
import dev.undine.domain.PushResult
import dev.undine.domain.RefName
import dev.undine.domain.RemoteGateway
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 원격에 올라간 것은 앱이 되돌리지 못한다 — 화면이 그대로 보여줄 수 있는 사유다. */
private const val PUSHED_IRREVERSIBLE_REASON =
    "원격에 올라간 변경은 앱이 되돌리지 못합니다 — 원격에서 직접 되돌려야 합니다."

/**
 * push 실행 결과.
 *
 * @property undoRecordFailure null 이 아니면 **push 는 끝났고 실행 이력 항목만 남지 않았다.**
 *   되돌릴 수 없는 연산이라도 기록을 건너뛰지 않는 이유는, 건너뛰면 사용자가 그 동작이 있었다는
 *   사실조차 이력에서 보지 못하기 때문이다.
 */
data class PushOutcome(
    val result: PushResult,
    val undoRecordFailure: UndineException?,
)

/**
 * 참조 하나를 원격에 올리고, 성공한 push 를 **되돌릴 수 없다는 사유와 함께** 기록한다.
 *
 * `force = true` 는 원격 이력을 덮어써 되돌릴 수 없다. **사용자 확인은 툴바가 받고**
 * (`RemoteToolbar` 의 force push 확인), 백업 ref 와 force-with-lease 는 `RemoteGatewayImpl` 이
 * 이미 책임진다 — 이 층은 확인된 의도를 그대로 옮기기만 한다.
 *
 * 거절([PushResult.Rejected])은 실패가 아니라 결과다. 예외로 바꾸지 않고, **기록도 남기지 않는다** —
 * 거절된 push 는 원격을 바꾸지 않았으므로 이력에 남길 사건이 없다.
 *
 * **전송 자체는 취소할 수 있게 둔다.** 다른 기록 경로처럼 전송까지 [NonCancellable] 로 묶으면
 * 툴바의 취소 버튼(`RemoteToolbarState.cancel`)이 아무 일도 하지 못하고, 응답 없는 원격에서
 * 빠져나올 방법이 사라진다 — 취소 가능한 원격 연산을 취소 불가로 바꾸는 것은 이 티켓의 범위가
 * 아니다. 대신 **전송이 끝난 뒤의 기록만** [NonCancellable] 로 감싸, 원격이 이미 받았는데 기록만
 * 빠지는 창을 닫는다 (결정 A-L2). 전송 도중의 취소는 그대로 전파되고 그때는 기록도 남지 않는다.
 */
class PushRemoteUseCase(
    private val remoteGateway: RemoteGateway,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(ref: RefName, force: Boolean, onProgress: (Progress) -> Unit): PushOutcome {
        // 시작 전 취소는 그대로 존중한다 — 아직 아무것도 올리지 않았다.
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            val result = remoteGateway.push(ref, force, onProgress)
            withContext(NonCancellable) {
                when (result) {
                    PushResult.Accepted -> PushOutcome(result, recordPushed(ref))
                    is PushResult.Rejected -> PushOutcome(result, undoRecordFailure = null)
                }
            }
        }
    }

    private suspend fun recordPushed(ref: RefName): UndineException? =
        operationRecorder.recordQuietly(GitOperationKind.PUSH) {
            operationRecorder.recordIrreversible(
                GitOperationKind.PUSH,
                PUSHED_IRREVERSIBLE_REASON,
                ref.value,
            )
        }
}
