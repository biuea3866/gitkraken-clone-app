package dev.undine.domain.undo

/**
 * **저장소를 바꾸고 그 변경을 기록하기까지**를 한 단위로 직렬화하는 구역.
 *
 * 변경 자체는 저장소 핸들의 임계 구역 안에서 끝나지만, [UndoStack] 기록은 그 구역이 풀린 **뒤**에
 * 일어난다. 그 사이에 다른 변경이 끼어들면 변경 순서와 기록 순서가 역전된다 — 변경 A → 변경 B →
 * B 기록 → A 기록 이면 스택 최상단이 A 인데 A 의 기준 상태는 B 이전이라, 되돌리기가
 * `UndoOutcome.ExternalChange` 로 **거부된다** (결정 G32). 화면이 여럿이면 두 변경이 서로 다른
 * 코루틴에서 오므로 그 창은 실재한다.
 *
 * 그래서 기록이 끝날 때까지 다음 변경을 들이지 않는다. **구현은 그 저장소 핸들을 소유한 쪽**
 * (`GitAccess`)이다 — 직렬화는 자원의 소유자가 하고 소비자가 하지 않는다 (결정 A-N1·G4). 그래서
 * 이 계약이 domain 에 있고 application(`OperationRecorder`)은 이 이름만 안다.
 *
 * **순번 정렬을 택하지 않은 이유**는 [OperationRecorder.recordingChange] 에 적혀 있다.
 */
interface ChangeRecordingOrder {

    /**
     * [block] 안의 **변경과 기록**이 끝날 때까지 다른 [withOrderedChange] 를 들이지 않는다.
     *
     * 이 구역은 저장소 핸들의 임계 구역보다 **바깥**이다. `block` 안에서 Gateway 를 부르면 그때
     * 핸들 락을 잡으므로, 순서는 항상 (기록 구역 → 핸들 락) 한 방향이고 서로를 기다리지 않는다.
     *
     * 되돌리기 실행은 이 구역에 참여하지 않는다 — 기록이 아직 도착하지 않은 창에서 되돌리기가
     * 실행되면 최상단의 기준 상태가 지금과 달라 **거부**되므로, 막지 않아도 안전한 쪽으로 닫힌다.
     */
    suspend fun <T> withOrderedChange(block: suspend () -> T): T
}
