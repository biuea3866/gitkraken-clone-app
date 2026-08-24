package dev.undine.application.welcome

import dev.undine.domain.OpenedRepository
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.RepositoryPath
import dev.undine.domain.SettingsGateway

/**
 * 로컬 저장소를 열고 최근 목록 맨 앞으로 올린다.
 *
 * **열기가 먼저다** — 열지 못한 경로를 최근 목록에 올리면 다음 실행 때 못 여는 경로가 최신으로 남는다.
 * 열기 실패([dev.undine.domain.UndineException.InvalidRepositoryPath] 등)는 감싸지 않고 그대로 올린다.
 * 사유별 안내는 화면이 판단한다.
 */
class OpenRepositoryUseCase(
    private val repositoryGateway: RepositoryGateway,
    private val settingsGateway: SettingsGateway,
) {
    suspend fun execute(path: RepositoryPath): OpenedRepository {
        val opened = repositoryGateway.open(path)
        settingsGateway.recordMostRecent(path)
        return opened
    }
}

/** 최근 목록 갱신 — 열기와 클론이 성공 뒤에 똑같이 밟는 경로다. */
internal suspend fun SettingsGateway.recordMostRecent(path: RepositoryPath) {
    val settings = load()
    save(settings.copy(recentRepositories = settings.recentRepositories.withMostRecent(path)))
}
