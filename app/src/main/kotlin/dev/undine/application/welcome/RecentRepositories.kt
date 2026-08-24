package dev.undine.application.welcome

import dev.undine.domain.RepositoryPath

/**
 * [path] 를 최근 목록 맨 앞(=최신)으로 옮긴다. 이미 있던 자리는 비운다.
 *
 * **상한 20개 절단은 여기서 하지 않는다** — 중복 제거와 절단은 `SettingsGateway.save` 가 저장 시점에
 * 이미 하는 일이라, 여기서 또 하면 상한이 두 곳에 생겨 한쪽만 바뀔 때 조용히 어긋난다.
 */
internal fun List<RepositoryPath>.withMostRecent(path: RepositoryPath): List<RepositoryPath> =
    listOf(path) + filterNot { it == path }

/** [path] 를 최근 목록에서 뺀다. 나머지 순서는 그대로 둔다. */
internal fun List<RepositoryPath>.without(path: RepositoryPath): List<RepositoryPath> =
    filterNot { it == path }
