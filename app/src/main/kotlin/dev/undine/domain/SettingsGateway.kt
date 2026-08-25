package dev.undine.domain

/** 최근 저장소·환경 설정 영속화 계약. 저장 위치·포맷은 infrastructure 가 정한다. */
interface SettingsGateway {

    suspend fun load(): Settings

    suspend fun save(settings: Settings)

    /**
     * 읽기–수정–쓰기를 **한 임계구역 안에서** 끝낸다. 전체 스냅샷을 저장하는 계약이라
     * [load] 로 읽고 [save] 로 쓰는 사이에 다른 갱신이 끼어들면 그 갱신이 통째로 사라진다.
     * 한 필드만 바꾸려는 소비자는 [load]·[save] 대신 이 연산을 쓴다.
     *
     * 동기화는 **이 자원의 Gateway 가 소유한다** — 소비자가 자기 락을 덧대지 않는다.
     *
     * [transform] 이 던지는 예외는 그대로 올라오고 아무것도 쓰지 않는다.
     * 결과가 읽은 값과 같으면 쓰지 않는다 — 바꿀 것이 없는 갱신은 파일을 건드리지 않는다.
     */
    suspend fun update(transform: (Settings) -> Settings)
}
