package dev.undine.presentation.sidebar

import androidx.compose.runtime.Immutable
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.UndineException

/**
 * 참조 목록 로딩 상태.
 *
 * 실패를 [Ready] 의 빈 목록으로 표현하지 않는 것이 핵심이다 — 그러면 화면이 "브랜치가 없다" 로
 * 잘못 안내한다 (exception-handling 규칙 7).
 */
@Immutable
sealed interface SidebarStatus {

    /** 아직 저장소를 열지 않았다. */
    data object Idle : SidebarStatus

    data object Loading : SidebarStatus

    data class Ready(val refs: SidebarRefs) : SidebarStatus

    data class Failed(val cause: UndineException) : SidebarStatus
}
