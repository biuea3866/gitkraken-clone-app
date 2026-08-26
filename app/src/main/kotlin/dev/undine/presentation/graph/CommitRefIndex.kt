package dev.undine.presentation.graph

import androidx.compose.runtime.Immutable
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.Tag

/** 칩의 종류. 색과 순서가 종류마다 달라 화면이 구분해 그릴 수 있어야 한다. */
enum class GraphRefKind {
    HEAD,
    BRANCH,
    TAG,
}

/**
 * 커밋 행에 붙는 참조 칩 하나.
 *
 * @property refName 브랜치·태그의 참조 이름. HEAD 칩은 `null` 이다 — HEAD 표기는 데이터가 아니라
 *   로케일이 정하므로 라벨을 여기 담지 않고 렌더 시점에 `graph.head` 에서 읽는다 ([RefChip]).
 */
@Immutable
data class GraphRefChip(
    val refName: String?,
    val kind: GraphRefKind,
    val target: CommitId? = null,
    val isAnnotated: Boolean = false,
)

/**
 * 커밋 → 참조 칩 색인. 행마다 브랜치·태그 목록을 훑지 않도록 **한 번 만들어 두고 조회**한다.
 *
 * HEAD 칩은 [of] 에 넘긴 `currentBranch` 가 브랜치 목록에서 실제로 찾아질 때만 만든다 —
 * detached HEAD 는 `currentBranch == null` 로 판정되고 칩을 그리지 않는다 (wave 3 결정 A4·§UND-14).
 * HEAD 판정에 필요한 값을 상시 갱신하는 것은 배선 티켓(UND-26) 몫이며, 이 화면은 받은 값을 표시만 한다.
 */
@Immutable
class CommitRefIndex private constructor(
    private val chipsByCommit: Map<CommitId, List<GraphRefChip>>,
) {
    /** [commit] 에 붙는 칩. HEAD → 브랜치 → 태그 순서이며, 없으면 빈 목록이다. */
    fun chipsFor(commit: CommitId): List<GraphRefChip> = chipsByCommit[commit].orEmpty()

    companion object {

        /** 참조를 아직 모르는 상태. 배선 전에도 그래프가 그려져야 한다. */
        val EMPTY: CommitRefIndex = CommitRefIndex(emptyMap())

        /**
         * @param currentBranch 현재 브랜치 이름. `null` 이거나 [branches] 에 없으면 HEAD 칩을 만들지 않는다.
         */
        fun of(
            branches: List<Branch>,
            tags: List<Tag>,
            currentBranch: RefName?,
        ): CommitRefIndex {
            val grouped = LinkedHashMap<CommitId, MutableList<GraphRefChip>>()
            headTarget(branches, currentBranch)?.let { target ->
                grouped.getOrPut(target) { mutableListOf() } +=
                    GraphRefChip(refName = null, kind = GraphRefKind.HEAD, target = target)
            }
            branches.forEach { branch ->
                grouped.getOrPut(branch.target) { mutableListOf() } +=
                    GraphRefChip(branch.name.value, GraphRefKind.BRANCH, target = branch.target)
            }
            tags.forEach { tag ->
                grouped.getOrPut(tag.target) { mutableListOf() } +=
                    GraphRefChip(tag.name.value, GraphRefKind.TAG, target = tag.target, isAnnotated = tag.isAnnotated)
            }
            return CommitRefIndex(grouped.mapValues { (_, chips) -> chips.toList() })
        }

        private fun headTarget(branches: List<Branch>, currentBranch: RefName?): CommitId? =
            currentBranch?.let { name -> branches.firstOrNull { it.name == name }?.target }
    }
}
