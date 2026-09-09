@file:Suppress("MatchingDeclarationName")

package dev.undine.presentation.graph

import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.contextmenu.GraphMenuEntry
import dev.undine.presentation.contextmenu.GraphOperationKind
import dev.undine.presentation.contextmenu.kind
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.GraphDragDropStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.graphDragDrop
import dev.undine.presentation.palette.Command
import dev.undine.presentation.palette.CommandAvailability
import dev.undine.presentation.palette.CommandId

/** 컨텍스트 메뉴와 팔레트가 공통으로 쓰는 다섯 그래프 조작 콜백. */
class GraphOperationCallbacks(private val state: GraphDragDropState) {
    private val requested = mutableListOf<GraphOperation>()

    val lastRequested: List<GraphOperation> get() = requested.toList()

    fun merge(source: RefName, target: RefName) =
        request(GraphOperation.Merge(source, BranchTarget.Named(target)))

    fun rebase(branch: RefName, upstream: RefName) =
        request(GraphOperation.Rebase(BranchTarget.Named(branch), upstream))
    fun cherryPick(commit: CommitId, target: RefName) =
        request(GraphOperation.CherryPick(commit, BranchTarget.Named(target)))
    fun reset(branch: RefName, to: CommitId) = request(GraphOperation.ResetBranch(branch, to))
    fun moveTag(tag: RefName, to: CommitId) = request(GraphOperation.MoveTag(tag, to))

    /** 이미 구성된 명령은 [BranchTarget.Current] 를 보존해 UseCase까지 전달한다. */
    fun request(operation: GraphOperation) {
        requested += operation
        state.request(operation)
    }
}

/**
 * 팔레트에 등록할 명령 정의만 만든다. 여기서는 등록·DI·App 배선을 하지 않는다.
 *
 * [selectedEntries] 는 **지금 지목한 그래프 대상의 메뉴 항목 전부**다 — 컨텍스트 메뉴가 쓰는
 * [dev.undine.presentation.contextmenu.graphMenuEntriesFor] 와 같은 함수의 결과다 (결정 D2·D10).
 * 대상 하나에서 조작 하나만 나온다는 옛 전제로는 브랜치를 지목했을 때 merge·rebase 가 동시에
 * 가능한 것을 표현할 수 없어, `graph.merge`·`rebase`·`resetBranch`·`moveTag` 네 명령이 **언제나
 * 비활성**이었다 (결정 D9-2).
 *
 * **가용성 판정을 팔레트가 다시 하지 않는다.** 조작이 목록에 있는지만 보면 공용 함수가 막은 것이
 * 팔레트에서는 활성으로 새어 나간다 — detached HEAD 에서 CherryPick·Merge·Rebase 가 그랬다.
 * 항목이 들고 있는 사유를 그대로 읽고, 막힌 항목은 실행 경로로 보내지 않는다 (결정 D17).
 */
fun graphOperationCommands(
    callbacks: GraphOperationCallbacks,
    copy: GraphDragDropStrings = builtInStringCatalog().stringsFor(DEFAULT_LOCALE).graphDragDrop,
    selectedEntries: () -> List<GraphMenuEntry>,
): List<Command> = listOf(
    command("graph.merge", copy.commandMerge, GraphOperationKind.MERGE, callbacks::request),
    command("graph.rebase", copy.commandRebase, GraphOperationKind.REBASE, callbacks::request),
    command("graph.cherryPick", copy.commandCherryPick, GraphOperationKind.CHERRY_PICK, callbacks::request),
    command("graph.resetBranch", copy.commandReset, GraphOperationKind.RESET_BRANCH, callbacks::request),
    command("graph.moveTag", copy.commandMoveTag, GraphOperationKind.MOVE_TAG, callbacks::request),
).map { prototype ->
    val matching = { selectedEntries().firstOrNull { entry -> entry.operation.kind() == prototype.kind } }
    Command(
        id = prototype.id,
        title = prototype.title,
        availability = {
            val entry = matching()
            val reason = entry?.blockedReason
            when {
                // 이 대상으로는 아예 만들 수 없는 조작이다 — 사유가 다르므로 문구도 다르다.
                entry == null -> CommandAvailability.Blocked(copy.unavailableCommand)
                reason != null -> CommandAvailability.Blocked(copy.refusal(reason))
                else -> CommandAvailability.Available
            }
        },
        // 막힌 항목은 실행하지 않는다 — 팔레트에서 눌러 본 다음에 실패를 보는 일이 없어야 한다.
        action = { matching()?.takeIf(GraphMenuEntry::enabled)?.let { entry -> prototype.action(entry.operation) } },
    )
}

private data class GraphOperationCommandPrototype(
    val id: CommandId,
    val title: String,
    val kind: GraphOperationKind,
    val action: (GraphOperation) -> Unit,
)

private fun command(
    id: String,
    title: String,
    kind: GraphOperationKind,
    action: (GraphOperation) -> Unit,
): GraphOperationCommandPrototype = GraphOperationCommandPrototype(CommandId(id), title, kind, action)
