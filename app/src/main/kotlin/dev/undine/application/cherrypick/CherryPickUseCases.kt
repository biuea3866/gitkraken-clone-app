package dev.undine.application.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickAbortConfirmation
import dev.undine.domain.cherrypick.CherryPickResult
import dev.undine.domain.cherrypick.CherryPickService

/**
 * 고른 커밋의 변경만 현재 브랜치로 가져온다.
 *
 * UseCase 는 얇다 — 적용 순서·워킹트리 검사 같은 판단은 [CherryPickService] 에 있고 여기서는 순서만
 * 엮는다. 결과를 바꾸거나 삼키지 않고 그대로 올린다: 충돌과 "이미 적용됨" 은 [CherryPickResult] 로,
 * 시작 전 실패는 예외로 presentation 까지 간다.
 *
 * @throws UndineException.DirtyWorkingTree 커밋되지 않은 변경이 있어 시작하지 않았을 때
 */
class CherryPickCommitsUseCase(private val cherryPickService: CherryPickService) {

    suspend fun execute(commits: List<CommitId>, recordOrigin: Boolean = false): CherryPickResult =
        cherryPickService.cherryPick(commits, recordOrigin)
}

/**
 * 충돌을 해결한 뒤 멈춘 커밋을 마무리한다.
 *
 * @throws UndineException.StateViolation cherry-pick 이 진행 중이 아닐 때
 */
class ContinueCherryPickUseCase(private val cherryPickService: CherryPickService) {

    suspend fun execute(): CherryPickResult = cherryPickService.continueAfterResolve()
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
