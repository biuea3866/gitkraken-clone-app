package dev.undine.application.preferences

import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway

/**
 * 설정 한 부분을 바꿔 곧바로 저장한다. 설정 화면에는 저장 버튼이 없고 이 경로 하나만 있다.
 *
 * **전체 스냅샷을 덮어쓰지 않고 [SettingsGateway.update] 로 부분 갱신한다** — 화면이 읽어 둔
 * 값과 저장된 값 사이에 다른 갱신(창 크기·탭 변경 등)이 끼어들면 스냅샷 저장은 그 갱신을 통째로
 * 지운다. 읽기–수정–쓰기의 원자성은 그 자원의 Gateway 가 소유한다.
 *
 * 전체 초기화도 이 경로를 쓴다 — 초기화는 `Settings.withDefaultPreferences()` 를 넘긴 변경일 뿐이다.
 * 설정 변경은 Git 연산이 아니므로 Undo 스택에 기록하지 않는다.
 */
class UpdatePreferencesUseCase(
    private val settingsGateway: SettingsGateway,
) {
    /** @return 저장된 값에 [change] 를 적용한 결과. 화면은 이 값으로 자기 상태를 맞춘다. */
    suspend fun execute(change: (Settings) -> Settings): Settings {
        var applied: Settings? = null
        settingsGateway.update { stored -> change(stored).also { applied = it } }
        return requireNotNull(applied) { "설정 갱신이 새 값을 만들지 않았습니다" }
    }
}
