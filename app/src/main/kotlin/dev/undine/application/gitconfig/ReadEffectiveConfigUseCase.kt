package dev.undine.application.gitconfig

import dev.undine.domain.RepositoryPath
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigGateway
import dev.undine.domain.gitconfig.GitConfigKey

/**
 * 지금 무엇이 실제로 적용되는지 — Git 설정 아홉 개의 실효값과 출처를 읽는다.
 *
 * **읽기 전용이다.** 결과를 손보지 않고 그대로 통과시킨다: 부재를 기본값으로 채우거나 실패를
 * 빈 결과로 접으면, 앱에서 바꿨는데 안 먹는 이유를 사용자가 알 방법이 다시 사라진다.
 * 앱 설정과의 결합은 이 UseCase 가 아니라 소비자(UND-82)의 몫이다.
 */
class ReadEffectiveConfigUseCase(
    private val gitConfigGateway: GitConfigGateway,
) {
    /** [repository] 가 `null` 이면 전역·시스템 설정만 읽는다 — 저장소가 열려 있지 않은 상태다. */
    suspend fun execute(repository: RepositoryPath?): Map<GitConfigKey, EffectiveValue> =
        gitConfigGateway.effectiveValues(repository)
}
