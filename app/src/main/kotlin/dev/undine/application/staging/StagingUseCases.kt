package dev.undine.application.staging

import dev.undine.application.undo.OperationRecorder
import dev.undine.domain.CommitResult
import dev.undine.domain.DiffHunk
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.StagingGateway
import dev.undine.domain.UndineException
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.undo.GitOperationKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 워킹트리 상태 조회. 스테이징 패널의 두 목록(staged·unstaged)이 이 결과로 그려진다.
 *
 * `RepositoryGateway` 를 경유하는 얇은 지점이다 — presentation 이 Gateway 를 직접 주입받지 않게 한다.
 */
class LoadWorkingTreeStatusUseCase(private val repositoryGateway: RepositoryGateway) {

    suspend fun execute(): WorkingTreeStatus = repositoryGateway.status()
}

/** 파일을 인덱스에 올린다. 다중 선택을 그대로 받는다 — 파일마다 왕복하면 목록이 중간 상태로 흔들린다. */
class StageFilesUseCase(private val stagingGateway: StagingGateway) {

    suspend fun execute(paths: List<String>) {
        if (paths.isEmpty()) return
        stagingGateway.stage(paths)
    }
}

/** 파일을 인덱스에서 내린다. */
class UnstageFilesUseCase(private val stagingGateway: StagingGateway) {

    suspend fun execute(paths: List<String>) {
        if (paths.isEmpty()) return
        stagingGateway.unstage(paths)
    }
}

/**
 * hunk 단위 스테이징. Diff 뷰어(UND-16)가 올리는 콜백이 여기로 온다 —
 * 스테이징 상태의 단일 소유자는 패널이고, 뷰어는 "이 hunk 를 올리고 싶다" 만 알린다.
 */
class StageHunksUseCase(private val stagingGateway: StagingGateway) {

    suspend fun execute(path: String, hunks: List<DiffHunk>) {
        if (hunks.isEmpty()) return
        stagingGateway.stageHunks(path, hunks)
    }
}

/**
 * 새 커밋을 만들고 되돌리기를 기록한다.
 *
 * amend 는 이 UseCase 가 겸하지 않는다 — HEAD 를 다시 쓰는 파괴적 연산이라
 * 확인 절차를 가진 [AmendCommitUseCase] 가 따로 소유한다.
 *
 * 커밋과 기록을 한 [NonCancellable] 단위로 묶는다 — 커밋이 끝난 뒤 취소가 떨어져 기록을 건너뛰면
 * 저장소에는 커밋이 있는데 되돌릴 방법이 없다 (결정 A-L2). 묶기 **전에** 호출자의 취소를 확인해,
 * 아직 아무것도 바꾸지 않은 시점의 취소는 존중한다.
 *
 * 되돌릴 지점([CommitResult.previousHead])과 기준 상태는 **커밋과 같은 임계 구역**에서 Gateway 가
 * 캡처해 준다 — 여기서 다시 읽으면 그 사이의 다른 조작까지 반영된다 (UND-73).
 *
 * @throws dev.undine.domain.UndineException.NothingToCommit 스테이징된 변경이 없을 때
 * @throws dev.undine.domain.UndineException.AuthorNotConfigured 작성자 정보가 없을 때
 */
class CommitStagedUseCase(
    private val stagingGateway: StagingGateway,
    private val operationRecorder: OperationRecorder,
) {

    suspend fun execute(message: String): CommitOutcome {
        currentCoroutineContext().ensureActive()
        return operationRecorder.recordingChange {
            withContext(NonCancellable) {
                val result = stagingGateway.commit(message)
                CommitOutcome(result, operationRecorder.recordCommit(result))
            }
        }
    }
}

/**
 * 커밋 결과를 `COMMIT` 기록으로 남긴다. 새 커밋과 amend 가 같은 경로를 쓴다 — 둘 다 "변경 직전
 * HEAD 로 soft reset" 이 되돌리기이고, amend 의 원본은 백업 ref 가 살려 둔다.
 */
internal suspend fun OperationRecorder.recordCommit(result: CommitResult): UndineException? =
    recordSoftReset(
        operation = GitOperationKind.COMMIT,
        previousHead = result.previousHead,
        baseline = result.baseline,
        targetLabel = result.commitId.value,
    )
