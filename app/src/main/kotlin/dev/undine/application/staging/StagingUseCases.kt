package dev.undine.application.staging

import dev.undine.domain.CommitResult
import dev.undine.domain.DiffHunk
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.StagingGateway
import dev.undine.domain.WorkingTreeStatus

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
 * 새 커밋을 만든다.
 *
 * amend 는 이 UseCase 가 겸하지 않는다 — HEAD 를 다시 쓰는 파괴적 연산이라
 * 확인 절차를 가진 [AmendCommitUseCase] 가 따로 소유한다.
 *
 * @throws dev.undine.domain.UndineException.NothingToCommit 스테이징된 변경이 없을 때
 * @throws dev.undine.domain.UndineException.AuthorNotConfigured 작성자 정보가 없을 때
 */
class CommitStagedUseCase(private val stagingGateway: StagingGateway) {

    suspend fun execute(message: String): CommitResult = stagingGateway.commit(message)
}
