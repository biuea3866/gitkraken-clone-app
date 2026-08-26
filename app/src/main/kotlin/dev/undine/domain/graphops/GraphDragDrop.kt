package dev.undine.domain.graphops

import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName

/**
 * 그래프에서 끌 수 있는 것. 화면이 무엇을 집었는지를 값으로 고정해, 판정이 칩 렌더 상태에
 * 의존하지 않게 한다.
 *
 * [Branch.target]·[Tag.target] 은 **드래그 시작 시점의 스냅샷**이며 "바뀔 것이 없는 드롭" 을 걸러내는
 * 데만 쓴다. 실제 조작이 쓰는 기대 target 은 조작 직전에 다시 읽는다
 * ([dev.undine.application.graphops.ExecuteGraphOperationUseCase]).
 */
sealed interface GraphDragSource {

    data class Branch(val name: RefName, val target: CommitId) : GraphDragSource

    /** [isAnnotated] 는 이동 가능 여부를 가른다 — annotated 태그를 옮기면 메시지와 tagger 를 잃는다. */
    data class Tag(val name: RefName, val target: CommitId, val isAnnotated: Boolean) : GraphDragSource

    data class Commit(val id: CommitId) : GraphDragSource
}

/** 드롭을 받을 수 있는 것 — 참조 칩이 붙은 브랜치이거나 커밋 행이다. */
sealed interface GraphDropTarget {

    data class Branch(val name: RefName, val target: CommitId) : GraphDropTarget

    data class Commit(val id: CommitId) : GraphDropTarget
}

/**
 * 그래프 조작 다섯 가지. **수행 브랜치를 [BranchTarget] 으로 들고 있는 것이 요점이다** —
 * "현재 브랜치에서" 를 화면의 `isCurrent` 스냅샷으로 미리 풀면, 읽은 뒤 실행되기 전에 체크아웃이
 * 바뀌었을 때 지목하지 않은 브랜치에서 실행된다 (결정 G6·`BranchTarget` 문서).
 *
 * 드래그&드롭은 항상 이름으로 지목한 브랜치를 만들고, 컨텍스트 메뉴·팔레트의 "현재 브랜치에" 계열은
 * [BranchTarget.Current] 를 만들어 판정을 실행 시점에 맡긴다.
 */
sealed interface GraphOperation {

    /** 되돌릴 수 없는 변경을 만드는가. 화면은 이 값으로 확인 단계에 위험 경고를 붙인다. */
    val isDestructive: Boolean get() = false

    /** [source] 를 [into] 에 병합한다. 수행 브랜치는 **병합을 받는 쪽**이다. */
    data class Merge(val source: RefName, val into: BranchTarget) : GraphOperation

    /** [branch] 를 [upstream] 위로 재배치한다. 수행 브랜치는 **움직이는 쪽**이다 (결정 G6). */
    data class Rebase(val branch: BranchTarget, val upstream: RefName) : GraphOperation

    /** [commit] 을 [onto] 에 적용한다. */
    data class CherryPick(val commit: CommitId, val onto: BranchTarget) : GraphOperation

    /**
     * [branch] 를 [to] 로 되돌린다.
     *
     * 대상이 체크아웃돼 있으면 워킹트리까지 같이 옮겨지므로 커밋하지 않은 편집이 사라진다 —
     * 그래서 [isDestructive] 다. 수행 브랜치를 [BranchTarget] 으로 두지 않는 이유는 이 조작이
     * **이름으로 지목한 브랜치 하나**를 옮기는 것이고, 체크아웃 여부 판정은
     * [dev.undine.domain.WorktreeOpsGateway.hardResetBranch] 가 실행 시점 HEAD 로 하기 때문이다.
     */
    data class ResetBranch(val branch: RefName, val to: CommitId) : GraphOperation {
        override val isDestructive: Boolean get() = true
    }

    /** [tag] 를 [to] 로 옮긴다. lightweight 태그만 대상이다. */
    data class MoveTag(val tag: RefName, val to: CommitId) : GraphOperation
}

/** 드롭할 수 없는 이유. 화면이 사유별로 다른 문구를 보여줄 수 있도록 닫힌 목록으로 둔다. */
enum class GraphDropRefusal {

    /** 자기 자신 위에 놓았다. */
    SAME_REF,

    /** 이미 그 커밋을 가리키고 있어 바뀔 것이 없다. */
    SAME_COMMIT,

    /** 옮기면 메시지와 tagger 를 잃는 annotated 태그다. */
    ANNOTATED_TAG,

    /** 지원하는 네 조합(브랜치→브랜치·커밋→브랜치·브랜치→커밋·태그→커밋) 밖이다. */
    UNSUPPORTED_COMBINATION,
}

/**
 * 드롭했을 때 무엇을 할 수 있는지. 가능/불가를 한 타입으로 닫아 두면 화면이 불가 경로를 빠뜨릴 수 없다.
 */
sealed interface GraphDropProposal {

    /** 드롭할 수 있는가. 드래그 중 대상의 활성/비활성 표시가 이 값으로 갈린다. */
    val canDrop: Boolean

    /**
     * 실행할 수 있는 조작. 브랜치→브랜치는 병합·리베이스 둘이고 나머지는 하나다.
     * 비어 있는 [Available] 은 만들지 않는다.
     */
    data class Available(val choices: List<GraphOperation>) : GraphDropProposal {
        init {
            require(choices.isNotEmpty()) { "가능한 조작이 없으면 Available 이 아닙니다" }
        }

        override val canDrop: Boolean get() = true
    }

    /** 드롭 자체를 막는다 — 놓고 나서 실패 메시지를 보는 일이 없어야 한다. */
    data class Unavailable(val reason: GraphDropRefusal) : GraphDropProposal {
        override val canDrop: Boolean get() = false
    }
}

/**
 * [source] 를 [target] 위에 놓았을 때의 제안. **저장소를 읽지 않는 순수 판정**이라 드래그가 움직이는
 * 동안 매 프레임 물어도 된다.
 *
 * 여기서 거르는 것은 **구조적으로 성립하지 않는 드롭**뿐이다. "병합할 것이 없다" 처럼 저장소를 봐야
 * 아는 결과는 막지 않는다 — Gateway 가 `NoChange` 로 답하고 화면이 그대로 알린다.
 */
fun proposeGraphDrop(source: GraphDragSource, target: GraphDropTarget): GraphDropProposal =
    when {
        source is GraphDragSource.Branch && target is GraphDropTarget.Branch -> branchOntoBranch(source, target)
        source is GraphDragSource.Commit && target is GraphDropTarget.Branch -> cherryPick(source, target)
        source is GraphDragSource.Branch && target is GraphDropTarget.Commit -> resetBranch(source, target)
        source is GraphDragSource.Tag && target is GraphDropTarget.Commit -> moveTag(source, target)
        else -> GraphDropProposal.Unavailable(GraphDropRefusal.UNSUPPORTED_COMBINATION)
    }

/**
 * 병합과 리베이스의 **수행 브랜치가 서로 반대**다 (결정 G6). 병합은 받는 쪽(드롭 대상)에서,
 * 리베이스는 움직이는 쪽(드래그 소스)에서 실행한다 — 일관성을 이유로 맞추면 사용자 의도와 반대로 동작한다.
 */
private fun branchOntoBranch(
    source: GraphDragSource.Branch,
    target: GraphDropTarget.Branch,
): GraphDropProposal =
    if (source.name == target.name) {
        GraphDropProposal.Unavailable(GraphDropRefusal.SAME_REF)
    } else {
        GraphDropProposal.Available(
            listOf(
                GraphOperation.Merge(source = source.name, into = BranchTarget.Named(target.name)),
                GraphOperation.Rebase(branch = BranchTarget.Named(source.name), upstream = target.name),
            ),
        )
    }

private fun cherryPick(source: GraphDragSource.Commit, target: GraphDropTarget.Branch): GraphDropProposal =
    GraphDropProposal.Available(
        listOf(GraphOperation.CherryPick(commit = source.id, onto = BranchTarget.Named(target.name))),
    )

private fun resetBranch(source: GraphDragSource.Branch, target: GraphDropTarget.Commit): GraphDropProposal =
    if (source.target == target.id) {
        GraphDropProposal.Unavailable(GraphDropRefusal.SAME_COMMIT)
    } else {
        GraphDropProposal.Available(listOf(GraphOperation.ResetBranch(branch = source.name, to = target.id)))
    }

/** annotated 판정이 먼저다 — 같은 커밋에 놓았더라도 "옮길 수 없는 태그" 가 진짜 이유다. */
private fun moveTag(source: GraphDragSource.Tag, target: GraphDropTarget.Commit): GraphDropProposal = when {
    source.isAnnotated -> GraphDropProposal.Unavailable(GraphDropRefusal.ANNOTATED_TAG)
    source.target == target.id -> GraphDropProposal.Unavailable(GraphDropRefusal.SAME_COMMIT)
    else -> GraphDropProposal.Available(listOf(GraphOperation.MoveTag(tag = source.name, to = target.id)))
}
