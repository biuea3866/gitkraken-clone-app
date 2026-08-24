package dev.undine.application.conflict

import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.conflict.ConflictGateway
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.MergeService
import dev.undine.domain.merge.RebaseResult

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

/** 계속 결과. 화면은 이 값으로 "끝났다" 와 "아직 충돌이 남았다" 를 구분한다. */
sealed interface ContinueOutcome {

    /** 병합이 이어졌다. */
    data class Merged(val result: MergeResult) : ContinueOutcome

    /** 리베이스가 이어졌다. */
    data class Rebased(val result: RebaseResult) : ContinueOutcome
}

/**
 * 해결한 뒤 상위 병합·리베이스를 이어간다.
 *
 * 무엇을 이어갈지는 **호출부가 아는 저장소 상태**로 가른다 — 상태를 여기서 다시 읽으면 화면이 보고
 * 있는 상태와 어긋날 수 있고, 두 곳이 각자 읽으면 그 사이 값이 달라진다.
 *
 * @throws UndineException.StateViolation 병합·리베이스가 진행 중이 아닐 때
 */
class ContinueAfterResolveUseCase(private val mergeService: MergeService) {

    suspend fun execute(state: RepositoryState): ContinueOutcome = when (state) {
        RepositoryState.MERGING -> ContinueOutcome.Merged(mergeService.continueMerge())
        RepositoryState.REBASING -> ContinueOutcome.Rebased(mergeService.continueRebase())
        else -> throw UndineException.StateViolation("병합·리베이스가 진행 중이 아닙니다")
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
