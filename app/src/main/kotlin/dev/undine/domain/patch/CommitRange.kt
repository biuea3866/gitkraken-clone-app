package dev.undine.domain.patch

import dev.undine.domain.CommitId

/**
 * 내보낼 커밋 범위. `from` 은 **제외**, [to] 는 포함이다 (git 의 `from..to` 와 같다).
 *
 * 커밋 하나를 내보내는 것도 "부모..그 커밋" 이라는 **범위 하나**로 표현한다 —
 * 단일과 다중을 다른 타입으로 가르면 호출부가 같은 일을 두 갈래로 부르게 된다.
 *
 * [from] 이 `null` 이면 **루트부터** [to] 까지다. 루트 커밋에는 부모가 없으므로
 * "부모를 지정하라" 로는 첫 커밋을 표현할 수 없다.
 */
data class CommitRange(
    val from: CommitId?,
    val to: CommitId,
)
