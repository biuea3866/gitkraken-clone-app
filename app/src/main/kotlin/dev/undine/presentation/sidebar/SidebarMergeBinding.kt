package dev.undine.presentation.sidebar

import androidx.compose.runtime.Stable
import dev.undine.domain.Branch
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.contextmenu.GraphMenuEntry

/**
 * 사이드바 병합 항목이 실행 경로에 닿는 통로.
 *
 * **가용성 판정을 사이드바가 하지 않는다.** 그래프 조작의 산출과 "지금 할 수 있는가" 는
 * [dev.undine.presentation.contextmenu.graphMenuEntriesFor] 하나가 답하고, 이 배선은 그 결과만
 * 읽는다 — 진입점마다 `currentBranch` 를 다시 검사하면 그것이 두 번째 판정이고, 한 곳을 고쳤을 때
 * 나머지가 조용히 남는다 (결정 D2·D17).
 *
 * @property entryOf 그 브랜치를 병합 원본으로 삼는 항목. `null` 이면 이 대상에 성립하지 않는 조작이라
 *   항목은 비활성으로 남는다 (숨기지 않는다 — 결정 D3).
 * @property onRequest 고른 조작을 넘길 곳. 드래그&드롭과 같은 확인·Undo 경로를 그대로 지난다.
 */
@Stable
class SidebarMergeBinding(
    val entryOf: (Branch) -> GraphMenuEntry?,
    val onRequest: (GraphOperation) -> Unit,
)
