package dev.undine.presentation.sidebar

import androidx.compose.runtime.Immutable
import dev.undine.domain.Branch

/**
 * 브랜치 행의 ahead/behind 배지 값.
 *
 * 값은 [Branch] 에 이미 실려 있어 행마다 다시 조회하지 않는다. **둘 다 0 이면 배지 자체가 없다** —
 * `0↑ 0↓` 는 정보가 아니라 잡음이라서 [of] 가 `null` 을 돌려주고 화면이 아무 것도 그리지 않는다.
 * 한쪽만 0 인 경우는 그쪽 숫자만 감추고 배지는 남는다.
 */
@Immutable
data class SidebarBadge(val ahead: Int, val behind: Int) {

    companion object {

        fun of(branch: Branch): SidebarBadge? =
            if (branch.ahead == 0 && branch.behind == 0) {
                null
            } else {
                SidebarBadge(ahead = branch.ahead, behind = branch.behind)
            }
    }
}
