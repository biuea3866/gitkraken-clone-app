package dev.undine.presentation.contextmenu

import androidx.compose.runtime.Immutable
import dev.undine.domain.CommitId
import dev.undine.domain.RefName

/**
 * 우클릭이 지목한 그래프 대상. 화면이 무엇을 집었는지를 값으로 고정해, 조작 산출이 칩 렌더 상태에
 * 의존하지 않게 한다 ([dev.undine.domain.graphops.GraphDragSource] 와 같은 이유).
 *
 * [Branch.head]·[Tag.head] 는 **지금 그 참조가 가리키는 커밋**이다. 조작 대상 커밋이 아니라
 * "옮길 것이 없는가" 판정에만 쓴다.
 */
sealed interface GraphContextTarget {

    data class Commit(val id: CommitId) : GraphContextTarget

    /** [isRemote] 는 로컬 전용 조작(reset)을 가른다 — 원격 추적 ref 는 로컬에서 옮길 수 없다. */
    data class Branch(val name: RefName, val head: CommitId, val isRemote: Boolean) : GraphContextTarget

    /** [isAnnotated] 는 이동 가능 여부를 가른다 — annotated 태그를 옮기면 메시지와 tagger 를 잃는다. */
    data class Tag(val name: RefName, val head: CommitId, val isAnnotated: Boolean) : GraphContextTarget
}

/**
 * 조작 산출이 참조하는 **현재 선택**.
 *
 * @property commit 지금 고른 커밋. reset·태그 이동이 "어디로" 옮길지가 이 값이다 — 없으면 옮길
 *   곳이 없으므로 그 항목은 사유와 함께 비활성이 된다 (결정 D3·D10).
 * @property currentBranch 지금 체크아웃된 브랜치. 대상이 자기 자신인지 가르는 데만 쓴다 — 조작이
 *   수행할 브랜치는 이름 스냅샷이 아니라 [dev.undine.domain.BranchTarget.Current] 로 넘긴다.
 */
@Immutable
data class GraphContextSelection(
    val commit: CommitId? = null,
    val currentBranch: RefName? = null,
)
