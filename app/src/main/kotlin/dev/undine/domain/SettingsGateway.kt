package dev.undine.domain

/** 최근 저장소·환경 설정 영속화 계약. 저장 위치·포맷은 infrastructure 가 정한다. */
interface SettingsGateway {

    suspend fun load(): Settings

    suspend fun save(settings: Settings)
}
