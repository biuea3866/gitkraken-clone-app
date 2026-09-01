package dev.undine.domain.gitconfig

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException

/**
 * Git 설정 실효값 조회 계약. 구현은 `GitConfigGatewayImpl` 이다.
 *
 * **읽기 전용이다.** 이 계약에는 쓰기 연산이 없다 — 사용자가 명령행에서 정한 값을 앱이 덮으면
 * "내가 설정한 대로 안 된다" 가 반대 방향으로 생긴다.
 */
interface GitConfigGateway {

    /**
     * [GitConfigKey] 아홉 개의 실효값을 한 번에 읽는다.
     *
     * 값이 있는 키만 결과에 담긴다 — **키가 없는 것이 곧 부재**다. 부재를 앱 설정으로 대체하지
     * 않는다 (결정 G34 UND-75 1). 앱 설정과의 결합은 소비자(UND-82)가 한다.
     *
     * 같은 키가 여러 범위에 있으면 Git 우선순위(저장소 > 전역 > 시스템)로 하나를 고르고,
     * **고른 값이 실제로 있던 범위**를 [EffectiveValue.source] 로 돌려준다.
     *
     * @param repository 저장소 범위를 함께 볼 대상. **`null` 이면 전역·시스템만 본다** — 저장소가
     *   열려 있지 않아도 설정 화면은 실효값을 말할 수 있어야 한다. 저장소가 아닌 경로를 주면
     *   저장소 범위 없이 전역·시스템만 본다.
     * @throws UndineException.GitOperationFailed 설정 파일이 손상됐거나 읽히지 않을 때.
     *   **부재로 접지 않는다** — 접으면 손상된 파일이 "설정 안 함" 으로 보인다 (결정 G35 UND-75 2).
     */
    suspend fun effectiveValues(repository: RepositoryPath?): Map<GitConfigKey, EffectiveValue>
}
