package dev.undine.presentation.conflict

import dev.undine.application.conflict.AbortConflictedOperationUseCase
import dev.undine.application.conflict.ContinueAfterResolveUseCase
import dev.undine.application.conflict.LoadConflictContentUseCase
import dev.undine.application.conflict.LoadConflictedFilesUseCase
import dev.undine.application.conflict.ResolveConflictUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase

/**
 * 충돌 에디터가 쓰는 UseCase 묶음 (`WelcomeActions`·`StagingActions` 와 같은 방식).
 *
 * @param loadStatus abort 확인에 담을 "사라질 경로" 를 읽는다. 확인 목록이 지금 사라질 것과 같아야
 *   `MergeService` 가 중단을 허용한다.
 */
class ConflictActions(
    val loadFiles: LoadConflictedFilesUseCase,
    val loadContent: LoadConflictContentUseCase,
    val resolve: ResolveConflictUseCase,
    val continueAfterResolve: ContinueAfterResolveUseCase,
    val abort: AbortConflictedOperationUseCase,
    val loadStatus: LoadWorkingTreeStatusUseCase,
)
