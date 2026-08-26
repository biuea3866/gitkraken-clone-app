package dev.undine.application.submodule

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.CommitResult
import dev.undine.domain.StagingGateway
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.undo.GitOperationKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 서브모듈 변경을 되돌릴 수 없다고 기록하는 사유.
 *
 * `UndoStrategy` 에는 서브모듈 포인터를 되감는 변이가 없고 그 파일은 같은 wave 의 다른 티켓이
 * 소유한다. 되돌릴 수 없다고 **말하는 것**과 기록을 아예 남기지 않는 것은 다르다 — 기록이 없으면
 * Undo 가 이 연산을 건너뛴 채 그 앞의 연산을 되돌린다.
 */
private const val INIT_IRREVERSIBLE_REASON: String =
    "서브모듈 초기화로 받아온 작업 디렉터리는 앱이 되돌리지 않습니다 — 필요하면 서브모듈을 직접 정리하세요."

private const val UPDATE_IRREVERSIBLE_REASON: String =
    "서브모듈을 부모 기록으로 맞추면서 바뀐 작업 디렉터리는 앱이 이전 커밋으로 되돌리지 않습니다."

/** 서브모듈 목록을 presentation에 전달한다. */
class LoadSubmodulesUseCase(private val gateway: SubmoduleGateway) {
    suspend fun execute(): List<Submodule> = gateway.list()
}

/**
 * 아직 초기화되지 않은 서브모듈을 비재귀로 초기화한다.
 *
 * 변경과 기록을 [NonCancellable] 한 단위로 묶는다. 화면 수명 코루틴은 리컴포지션·화면 이탈로
 * 취소되는데, 변경이 성공한 뒤 기록 전에 끊기면 Git 은 바뀌었는데 스택에는 아무것도 남지 않는다 —
 * 그 뒤의 되돌리기가 이 연산을 건너뛰고 그 앞의 연산을 되돌린다.
 *
 * 기록은 변경이 **성공한 뒤에만** 남긴다. 하지 않은 일이 스택에 쌓이면 되돌리기가 엉뚱한 연산을
 * 되돌리므로, 변경 실패는 기록 없이 그대로 전파한다.
 *
 * 묶기 **전에** 호출자의 취소를 확인한다 — 아직 아무것도 바뀌지 않은 시점의 취소는 존중해야 한다.
 * [NonCancellable] 이 덮는 것은 "시작한 변경을 기록까지 끝내는" 구간뿐이다.
 */
class InitializeSubmoduleUseCase(
    private val gateway: SubmoduleGateway,
    private val recorder: OperationRecorder,
) {
    suspend fun execute(path: String) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            gateway.initialize(path, recursive = false)
            recorder.recordIrreversible(GitOperationKind.SUBMODULE_INIT, INIT_IRREVERSIBLE_REASON)
        }
    }
}

/** 초기화된 서브모듈을 부모가 기록한 커밋으로 맞춘다. 변경과 기록은 취소로 갈라지지 않는다. */
class UpdateSubmoduleUseCase(
    private val gateway: SubmoduleGateway,
    private val recorder: OperationRecorder,
) {
    suspend fun execute(path: String) {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            gateway.update(path, recursive = false)
            recorder.recordIrreversible(GitOperationKind.SUBMODULE_UPDATE, UPDATE_IRREVERSIBLE_REASON)
        }
    }
}

/**
 * 서브모듈 gitlink를 부모 저장소에 반영한다.
 *
 * `SubmoduleGateway`를 넓히지 않는다. gitlink도 부모의 경로 하나이므로, 확정 결정(E6)대로
 * 기존 스테이징·커밋 계약을 그대로 사용한다. 커밋 자체의 Undo 기록은 스테이징·커밋 경로가
 * 소유하므로 여기서 두 번 남기지 않는다.
 */
class CommitSubmodulePointerUseCase(private val stagingGateway: StagingGateway) {
    suspend fun execute(path: String, message: String): CommitResult {
        stagingGateway.stage(listOf(path))
        return stagingGateway.commit(message)
    }
}
