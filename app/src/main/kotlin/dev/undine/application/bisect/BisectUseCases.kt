package dev.undine.application.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectService
import dev.undine.domain.bisect.BisectSession
import dev.undine.domain.bisect.BisectVerdict

/**
 * good/bad 를 지정해 이분 탐색을 시작한다.
 *
 * UseCase 는 얇다 — 다음 대상 선택·확정 판단은 [BisectService] 에 있고 여기서는 호출만 엮는다.
 * 결과를 바꾸거나 삼키지 않는다: 역방향 경고·미지원·후보 목록은 [BisectResult] 로 그대로 올라가고,
 * 시작 전 실패만 예외로 간다.
 *
 * @throws UndineException.StateViolation 이미 진행 중인 세션이 있을 때
 */
class StartBisectUseCase(private val bisectService: BisectService) {

    suspend fun execute(good: CommitId, bad: CommitId): BisectResult = bisectService.start(good, bad)
}

/**
 * 체크아웃된 검사 대상에 good/bad/skip 을 판정하고 다음 대상으로 넘어간다.
 *
 * @throws UndineException.StateViolation 진행 중이 아니거나 판정할 대상이 없을 때
 */
class MarkBisectUseCase(private val bisectService: BisectService) {

    suspend fun execute(verdict: BisectVerdict): BisectResult = bisectService.mark(verdict)
}

/**
 * 탐색을 접고 시작 지점(브랜치 또는 detached 커밋)으로 되돌린다.
 *
 * @throws UndineException.StateViolation 진행 중인 세션이 없을 때
 */
class ResetBisectUseCase(private val bisectService: BisectService) {

    suspend fun execute() = bisectService.reset()
}

/**
 * 저장소에 남아 있는 진행 중 세션을 읽는다. 앱을 다시 켠 뒤 화면이 이어서 진행할 근거다.
 *
 * 세션이 없으면 null 이다 — 진행 중이 아닌 것은 실패가 아니다.
 */
class RestoreBisectSessionUseCase(private val bisectService: BisectService) {

    suspend fun execute(): BisectSession? = bisectService.currentSession()
}
