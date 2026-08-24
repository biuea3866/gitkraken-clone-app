package dev.undine.presentation.staging

import dev.undine.application.staging.AmendCommitUseCase
import dev.undine.application.staging.CommitStagedUseCase
import dev.undine.application.staging.LoadWorkingTreeStatusUseCase
import dev.undine.application.staging.StageFilesUseCase
import dev.undine.application.staging.StageHunksUseCase
import dev.undine.application.staging.UnstageFilesUseCase

/**
 * 스테이징 패널이 쓰는 UseCase 묶음 (`WelcomeActions` 와 같은 방식).
 *
 * 하나로 묶는 이유는 상태 홀더 생성자가 UseCase 를 하나씩 받으면 호출부가 인자 순서를 외워야 하고,
 * UseCase 를 더할 때마다 배선이 흔들리기 때문이다. 화면은 이 묶음만 받고 Gateway 를 알지 못한다.
 */
class StagingActions(
    val loadStatus: LoadWorkingTreeStatusUseCase,
    val stageFiles: StageFilesUseCase,
    val unstageFiles: UnstageFilesUseCase,
    val stageHunks: StageHunksUseCase,
    val commitStaged: CommitStagedUseCase,
    val amendCommit: AmendCommitUseCase,
)
