package dev.undine.domain.bisect

import dev.undine.domain.CommitId
import dev.undine.domain.UndineException

/**
 * 이분 탐색의 **저장소 접근**을 맡는 계약. 구현은 `BisectGatewayImpl` 이다.
 *
 * 탐색 규칙(어디를 다음에 검사할지·언제 확정할지)은 [BisectService] 가 갖고, 여기에는 "저장소에
 * 무엇을 묻고 무엇을 남기는가" 만 둔다.
 *
 * 세션 상태는 앱 설정이 아니라 **저장소의 `.git/` 하위 표준 bisect 상태**에 있다. 그래서 앱을 껐다
 * 켜도 복원되고, 외부 git CLI 로 같은 저장소를 다뤄도 상태가 두 벌로 갈라지지 않는다.
 */
interface BisectGateway {

    /**
     * 저장소에 남아 있는 진행 중 세션. 없으면 null 이다.
     *
     * @throws UndineException.StateViolation 세션 상태가 이 앱이 이어갈 수 없는 형태일 때
     *   (예: 외부 도구가 bad 를 지정하지 않고 시작한 세션)
     */
    suspend fun currentSession(): BisectSession?

    /** 지금 HEAD 가 있는 자리 — 세션에 기록해 두었다가 reset 이 여기로 되돌린다. */
    suspend fun startPoint(): BisectStartPoint

    /**
     * [good] 들과 [bad] 사이의 후보 구간을 훑는다. 커밋 그래프 질의라 domain 이 아니라 여기 있다.
     *
     * @throws UndineException.NotFound 그 커밋이 저장소에 없을 때
     */
    suspend fun surveyCandidates(good: List<CommitId>, bad: CommitId): CandidateSurvey

    /**
     * [session] 을 저장소의 bisect 상태로 기록한다 (시작·갱신 공통).
     *
     * **[expected] 와 저장소의 현재 세션이 같을 때만 기록한다.** 한 논리 전이(시작·판정)가 여러 번의
     * 저장소 접근으로 나뉘므로, 그 사이에 다른 호출이 끼어들었는지 쓰기 직전에 다시 확인해야 한다.
     * 확인 없이 쓰면 두 호출이 서로의 세션을 덮어써 HEAD 와 세션이 다른 커밋을 가리킨다.
     *
     * @param expected 이 전이를 계산할 때 읽은 세션. 시작이라 아직 세션이 없어야 하면 null 이다.
     * @throws UndineException.StateViolation 저장소의 세션이 [expected] 와 다를 때
     */
    suspend fun saveSession(expected: BisectSession?, session: BisectSession)

    /**
     * [probe] 를 검사할 수 있도록 체크아웃하고(HEAD 는 detached 가 된다) 그 사실을
     * **한 번의 저장소 접근 안에서** 기록한다. 기록되는 세션은 `expected.testing = probe` 다.
     *
     * 체크아웃과 기록을 나누면 그 사이에 취소·실패·다른 호출이 끼었을 때 HEAD 와 세션이 서로 다른
     * 커밋을 가리킨다. 순서는 **체크아웃이 먼저**다 — 기록이 먼저면 실패했을 때 검사하지도 않은
     * 커밋이 검사 중으로 남아 사용자가 엉뚱한 커밋에 판정을 붙인다.
     *
     * [saveSession] 과 같은 이유로 [expected] 를 먼저 대조한다. 체크아웃은 HEAD 를 옮기는 일이라
     * 남의 전이 위에 얹히면 되돌릴 자리를 잃는다.
     *
     * @param expected 이 걸음을 계산할 때 읽은 세션.
     * @throws UndineException.StateViolation 저장소의 세션이 [expected] 와 다를 때
     * @throws UndineException.NotFound 그 커밋이 저장소에 없을 때
     */
    suspend fun beginProbe(expected: BisectSession, probe: CommitId)

    /**
     * 기록된 시작 지점으로 되돌린 **뒤** bisect 상태를 지운다.
     *
     * 순서가 중요하다 — 상태를 먼저 지우면 복구 실패 시 어디로 돌아가야 하는지 알 수 없게 된다.
     * 지우지 못한 파일·참조가 남으면 성공으로 보고하지 않는다. 반쯤 지워진 상태를 성공이라고 하면
     * 다음 시작이 "이미 진행 중" 으로 막히고 사용자는 이유를 알 수 없다.
     *
     * 시작 지점만 읽으므로 [currentSession] 이 읽지 못하는 반쪽 상태에서도 되돌릴 수 있다.
     *
     * @throws UndineException.StateViolation 진행 중인 세션이 없을 때
     */
    suspend fun clearSession()
}
