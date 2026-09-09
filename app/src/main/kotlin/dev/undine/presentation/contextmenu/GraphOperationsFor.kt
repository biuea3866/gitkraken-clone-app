@file:Suppress("MatchingDeclarationName")

package dev.undine.presentation.contextmenu

import androidx.compose.runtime.Immutable
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphOperation

/**
 * 대상으로 만들 수 있는 그래프 조작. **컨텍스트 메뉴와 팔레트가 같은 함수를 쓴다** — 같은 질문을
 * 두 곳에서 따로 답하면 하나가 곧 틀린다 (결정 D2·D10).
 *
 * 저장소를 읽지 않는 순수 함수라 메뉴를 그리는 동안 매 프레임 물어도 된다.
 *
 * 대상별 산출 (결정 D10):
 *
 * | 대상 | 산출 |
 * |---|---|
 * | 커밋 | 현재 브랜치로 cherry-pick |
 * | 로컬 브랜치 | 현재 브랜치에 merge · 현재 브랜치를 그 위로 rebase · 그 브랜치를 선택 커밋으로 reset |
 * | 원격 브랜치 | merge · rebase (로컬 전용 조작인 reset 은 내지 않는다) |
 * | 태그 | 태그 이동 |
 *
 * **아예 성립하지 않는 조합은 목록에 넣지 않는다** — 원격 브랜치의 reset 이 그렇다. 대상에는
 * 성립하지만 지금 실행할 수 없는 것은 목록에 남기고 [graphMenuEntriesFor] 가 사유를 붙인다 (결정 D3).
 *
 * 수행 브랜치는 이름 스냅샷이 아니라 [BranchTarget.Current] 로 넘긴다 — 메뉴를 그린 뒤 실행되기
 * 전에 체크아웃이 바뀌면 지목하지 않은 브랜치에서 실행되기 때문이다 ([GraphOperation] 문서).
 * 그래서 merge 는 받는 쪽이, rebase 는 움직이는 쪽이 현재 브랜치다 (결정 G6 의 방향 규칙).
 */
fun graphOperationsFor(
    target: GraphContextTarget,
    selection: GraphContextSelection,
): List<GraphOperation> = when (target) {
    is GraphContextTarget.Commit ->
        listOf(GraphOperation.CherryPick(commit = target.id, onto = BranchTarget.Current))

    is GraphContextTarget.Branch -> buildList {
        add(GraphOperation.Merge(source = target.name, into = BranchTarget.Current))
        add(GraphOperation.Rebase(branch = BranchTarget.Current, upstream = target.name))
        if (!target.isRemote) {
            add(GraphOperation.ResetBranch(branch = target.name, to = selection.commit ?: target.head))
        }
    }

    is GraphContextTarget.Tag ->
        listOf(GraphOperation.MoveTag(tag = target.name, to = selection.commit ?: target.head))
}

/**
 * 지목한 대상의 메뉴 항목 — **지목한 것이 없으면 고른 커밋이 대상이다.**
 *
 * 팔레트가 쓰는 진입점이다. 그래프에서 무엇도 우클릭하지 않았다면 사용자가 닿을 수 있는 대상은
 * 고른 커밋뿐이고, 그때 나오는 것은 cherry-pick 하나다.
 *
 * **조작 목록이 아니라 항목(가용성 포함)을 돌려준다** — 팔레트가 목록만 받아 "있으면 활성" 으로
 * 판정하면 그것이 두 번째 판정이 되고, 공용 함수가 막은 것이 팔레트에서는 활성으로 새어 나간다
 * (결정 D17).
 */
fun graphMenuEntriesForPicked(
    target: GraphContextTarget?,
    selection: GraphContextSelection,
): List<GraphMenuEntry> {
    val chosen = target
        ?: selection.commit?.let(GraphContextTarget::Commit)
        ?: return emptyList()
    return graphMenuEntriesFor(chosen, selection)
}

/**
 * 대상에서 **특정 종류**의 항목 하나 — 없으면 `null`.
 *
 * 조작 하나만 내는 진입점(사이드바의 병합 항목)이 쓴다. 그 진입점도 자기 손으로 `GraphOperation` 을
 * 짓거나 `currentBranch` 를 다시 보지 않고, 여기서 받은 항목의 가용성만 읽는다 (결정 D17).
 */
fun graphMenuEntryOf(
    kind: GraphOperationKind,
    target: GraphContextTarget,
    selection: GraphContextSelection,
): GraphMenuEntry? = graphMenuEntriesFor(target, selection).firstOrNull { entry ->
    entry.operation.kind() == kind
}

/** 메뉴 항목 하나 — 조작과, 지금 실행할 수 없다면 그 사유. */
@Immutable
data class GraphMenuEntry(
    val operation: GraphOperation,
    val blockedReason: GraphDropRefusal?,
) {
    val enabled: Boolean get() = blockedReason == null
}

/**
 * 메뉴가 그릴 항목 목록. 목록 자체는 [graphOperationsFor] 하나가 산출하고, 여기서 항목별로
 * **지금 실행할 수 있는가**만 덧붙인다.
 */
fun graphMenuEntriesFor(
    target: GraphContextTarget,
    selection: GraphContextSelection,
): List<GraphMenuEntry> = graphOperationsFor(target, selection).map { operation ->
    GraphMenuEntry(operation, graphOperationBlockedReason(operation, target, selection))
}

/**
 * 대상에는 성립하지만 지금 실행할 수 없는 사유. `null` 이면 실행할 수 있다.
 *
 * 판정은 드래그&드롭의 거부 사유([GraphDropRefusal])와 같은 어휘를 쓴다 — 사용자가 드래그로
 * 만나던 문장과 메뉴에서 만나는 문장이 달라지면 같은 제약을 다른 규칙으로 읽는다.
 */
fun graphOperationBlockedReason(
    operation: GraphOperation,
    target: GraphContextTarget,
    selection: GraphContextSelection,
): GraphDropRefusal? {
    // detached HEAD 면 현재 브랜치 위에서 도는 조작은 수행할 브랜치 자체가 없다. 다른 사유보다
    // 먼저 본다 — "자기 자신인가" 는 비교할 현재 브랜치가 있어야 물을 수 있는 질문이다.
    if (operation.runsOnCurrentBranch() && selection.currentBranch == null) {
        return GraphDropRefusal.NO_CURRENT_BRANCH
    }
    return when (operation) {
        // 현재 브랜치를 자기 자신에 병합하거나 자기 위로 리베이스할 수는 없다.
        is GraphOperation.Merge -> GraphDropRefusal.SAME_REF.takeIf { operation.source == selection.currentBranch }
        is GraphOperation.Rebase -> GraphDropRefusal.SAME_REF.takeIf { operation.upstream == selection.currentBranch }
        // 옮길 곳(선택 커밋)이 없거나 이미 그 커밋을 가리키면 옮길 것이 없다.
        is GraphOperation.ResetBranch -> GraphDropRefusal.SAME_COMMIT.takeIf { operation.to == headOf(target) }
        is GraphOperation.MoveTag -> when {
            (target as? GraphContextTarget.Tag)?.isAnnotated == true -> GraphDropRefusal.ANNOTATED_TAG
            operation.to == headOf(target) -> GraphDropRefusal.SAME_COMMIT
            else -> null
        }
        // cherry-pick 이 실제로 가져올 변경이 있는지는 저장소를 봐야 안다 — 막지 않고 결과로 알린다.
        is GraphOperation.CherryPick -> null
    }
}

/**
 * 수행 브랜치가 [BranchTarget.Current] 인가 — detached HEAD 에서 막히는 것과 막히지 않는 것이
 * 이 값으로 갈린다.
 *
 * 조작을 열거하지 않고 **수행 브랜치 필드를 본다** — 새 조작이 늘면 sealed `when` 이 컴파일에서
 * 잡으므로, 현재 브랜치를 쓰는 조작이 판정 없이 새로 들어올 수 없다.
 */
private fun GraphOperation.runsOnCurrentBranch(): Boolean = when (this) {
    is GraphOperation.Merge -> into == BranchTarget.Current
    is GraphOperation.Rebase -> branch == BranchTarget.Current
    is GraphOperation.CherryPick -> onto == BranchTarget.Current
    // 이름으로 지목한 브랜치·태그 하나를 옮기는 조작이라 체크아웃과 무관하다.
    is GraphOperation.ResetBranch -> false
    is GraphOperation.MoveTag -> false
}

/** 그 대상이 지금 가리키는 커밋 — "옮길 것이 없는가" 판정의 기준점이다. */
private fun headOf(target: GraphContextTarget): CommitId = when (target) {
    is GraphContextTarget.Branch -> target.head
    is GraphContextTarget.Tag -> target.head
    is GraphContextTarget.Commit -> target.id
}
