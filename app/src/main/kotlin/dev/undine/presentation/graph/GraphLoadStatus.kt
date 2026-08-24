package dev.undine.presentation.graph

import androidx.compose.runtime.Immutable
import dev.undine.domain.UndineException

/**
 * 그래프 이력 로딩 상태.
 *
 * **실패를 빈 목록으로 대체하지 않는다** — [Failed] 는 [Loaded] 와 다른 상태이고, 빈 이력
 * (`Loaded` + 행 0건)과도 구분된다 (exception-handling 규칙 7). 취소는 상태가 아니다 —
 * 취소되면 [Idle] 로 되돌아가고 [kotlinx.coroutines.CancellationException] 이 그대로 전파된다.
 */
@Immutable
sealed interface GraphLoadStatus {

    /** 아직 요청하지 않았거나, 진행 중이던 첫 페이지 요청이 취소된 상태. */
    data object Idle : GraphLoadStatus

    /** 첫 페이지를 불러오는 중. */
    data object Loading : GraphLoadStatus

    /** 한 페이지 이상을 불러왔다. 행이 0건이어도 실패가 아니다. */
    data object Loaded : GraphLoadStatus

    /** 이력 로딩이 실패했다. [failure] 를 보존해 화면이 실패 종류별로 안내할 수 있게 한다. */
    data class Failed(val failure: UndineException) : GraphLoadStatus
}
