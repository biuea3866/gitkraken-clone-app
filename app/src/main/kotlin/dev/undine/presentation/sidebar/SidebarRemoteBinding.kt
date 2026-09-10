package dev.undine.presentation.sidebar

import androidx.compose.runtime.Stable
import dev.undine.domain.Branch
import dev.undine.presentation.toolbar.BranchRemoteEntry
import dev.undine.presentation.toolbar.BranchRemoteOperation

/**
 * 사이드바의 지목 받기·올리기가 실행 경로에 닿는 통로.
 *
 * **가용성 판정을 사이드바가 하지 않는다** ([SidebarMergeBinding] 과 같은 이유). "지금 할 수
 * 있는가" 는 원격 작업 상태 홀더 하나가 답하고, 이 배선은 그 결과만 읽는다 — 진입점마다 원격
 * 목록과 업스트림을 다시 검사하면 그것이 두 번째 판정이고, 한 곳을 고쳤을 때 나머지가 조용히
 * 남는다 (결정 D4).
 *
 * @property entryOf 그 브랜치를 대상으로 하는 항목. 막혀 있으면 사유가 실려 오고, 항목은
 *   숨기지 않고 비활성으로 남는다 (결정 D5).
 * @property onPull 지목 받기를 시작할 곳. 툴바의 받기로 위임하지 않는다 — 대상이 다르다 (결정 D8).
 * @property onPush 지목 올리기를 시작할 곳. 툴바와 같은 push 경로를 그 브랜치의 참조로 부른다.
 */
@Stable
class SidebarRemoteBinding(
    val entryOf: (Branch, BranchRemoteOperation) -> BranchRemoteEntry,
    val onPull: (Branch) -> Unit,
    val onPush: (Branch) -> Unit,
)
