package dev.undine.application.welcome

import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway

/**
 * 최근 저장소 목록을 읽는다. **앞이 최신**인 저장 순서를 그대로 돌려준다.
 *
 * 각 경로가 지금도 존재하는지는 판정하지 않는다 — 그 판정은 화면이 표시 직전에 한다
 * (사라진 경로를 조용히 지우지 않고 회색으로 남겨야 하므로, 판정 시점이 표시 시점이어야 한다).
 */
class LoadRecentRepositoriesUseCase(
    private val settingsGateway: SettingsGateway,
) {
    suspend fun execute(): List<RepositoryPath> = settingsGateway.load().recentRepositories
}
