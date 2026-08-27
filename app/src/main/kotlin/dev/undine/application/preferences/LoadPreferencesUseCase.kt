package dev.undine.application.preferences

import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway

/**
 * 설정 화면이 열릴 때 저장된 설정을 읽는다.
 *
 * 읽지 못한 경우를 여기서 기본값으로 접지 않는다 — 화면이 "기본값으로 열렸다" 는 사실을
 * 사용자에게 알려야 하므로, 실패는 그대로 올려 상태 홀더가 사유와 함께 처리한다.
 */
class LoadPreferencesUseCase(
    private val settingsGateway: SettingsGateway,
) {
    suspend fun execute(): Settings = settingsGateway.load()
}
