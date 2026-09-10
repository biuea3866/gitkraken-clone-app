package dev.undine.domain

/**
 * 여러 Gateway 호출로 이루어진 작업 하나를 **시작 시점의 저장소 세션**에 고정하는 구역.
 *
 * 호출이 하나뿐인 작업은 이 구역이 필요 없다 — 구현이 이미 그 호출 하나를 시작 세션에 묶는다
 * (UND-80). 문제는 `fetch → 조회 → 판정 → 조건부 갱신`처럼 **여러 호출로 이어지는 작업**이다.
 * 호출마다 시작 세션을 새로 잡으면 그 사이에 사용자가 저장소를 A 에서 B 로 바꿨을 때 앞 호출은
 * A 에, 뒤 호출은 B 에 적용된다. 그러면 변경은 B 에 가고 기록은 A 의
 * [dev.undine.domain.undo.UndoStack] 에 남아, 되돌리기가 엉뚱한 저장소를 건드린다.
 *
 * 그래서 **작업의 시작점**이 이 구역을 열고 그 안의 모든 저장소 접근이 같은 세션 키를 쓴다.
 * 구현은 그 세션을 소유한 쪽(`GitAccess`)이다 — 결속은 거기서 끝나고 Gateway·UseCase 시그니처로
 * 새지 않는다 (결정 A-N1·G4). 이 계약이 domain 에 있는 이유는
 * [dev.undine.domain.undo.ChangeRecordingOrder] 와 같다.
 */
interface RepositorySessionBinding {

    /**
     * [block] 안의 모든 저장소 접근을 **이 호출이 시작될 때 활성이던 세션**에 고정한다.
     *
     * 세션 키를 락 대기 **전에** 잡는 것이 요점이다 (UND-80). 잡아 둔 세션이 도중에 닫히면 활성
     * 세션으로 갈아타지 않고 실패한다 — 조용히 다른 저장소를 바꾸는 것보다 아무것도 하지 않고
     * 사유를 말하는 쪽이 안전하다.
     *
     * 이미 열려 있는 구역 안에서 다시 부르면 **바깥 구역의 세션을 그대로 쓴다**. 안쪽이 다시
     * 잡으면 바깥이 고정한 의미가 사라진다.
     *
     * @throws UndineException.StateViolation 저장소가 열려 있지 않을 때
     */
    suspend fun <T> withStartingSession(block: suspend () -> T): T
}
