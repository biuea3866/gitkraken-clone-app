package dev.undine.application.welcome

import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway

/**
 * 최근 목록에서 한 항목을 지운다.
 *
 * 사라진 경로를 앱이 알아서 지우지 않는 이유가 여기 있다 — 외장 디스크가 빠졌거나 네트워크 볼륨이
 * 안 붙은 순간에 목록이 비면 사용자가 복구할 방법이 없다. 삭제는 **명시적 요청**일 때만 한다.
 */
class ForgetRecentRepositoryUseCase(
    private val settingsGateway: SettingsGateway,
) {
    /** @return 남은 최근 목록. */
    suspend fun execute(path: RepositoryPath): List<RepositoryPath> {
        val settings = settingsGateway.load()
        val remaining = settings.recentRepositories.without(path)
        settingsGateway.save(settings.copy(recentRepositories = remaining))
        return remaining
    }
}
