package dev.undine.domain.undo

import dev.undine.domain.CommitId
import dev.undine.domain.RefName

/**
 * 되돌리기의 **기준 상태** — 기록 시점에 어느 브랜치의 어느 커밋 위에 있었는가.
 *
 * 되돌리기 직전 지금 상태와 비교해, 그 사이 앱 밖(터미널·다른 도구)에서 저장소가 바뀌었으면
 * 되돌리지 않는다. 비교 없이 실행하면 사용자가 의도하지 않은 커밋으로 reset 된다.
 *
 * [branch] 가 null 이면 브랜치 위가 아니다 — detached HEAD 이거나 커밋이 하나도 없는 저장소다.
 * 되돌리기는 브랜치 위에서만 허용하므로 이 값이 그 판단의 근거가 된다.
 */
data class RepositoryBaseline(
    val branch: RefName?,
    val head: CommitId?,
) {
    val isOnBranch: Boolean
        get() = branch != null
}
