@file:Suppress("MatchingDeclarationName")

package dev.undine.presentation.graph

import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
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
 * UND-51이 registry에 등록할 수 있도록 명령 정의만 만든다. 여기서는 등록·DI·App 배선을 하지 않는다.
 * [selectedOperation]은 메뉴/팔레트가 현재 선택으로 만들 수 있는 조작 하나이며, 맞는 명령만 활성화된다.
 */
fun graphOperationCommands(
    callbacks: GraphOperationCallbacks,
    copy: GraphDragDropStrings = builtInStringCatalog().stringsFor(DEFAULT_LOCALE).graphDragDrop,
    selectedOperation: () -> GraphOperation?,
): List<Command> = listOf(
    command("graph.merge", copy.commandMerge, GraphOperation.Merge::class.java, callbacks::request),
    command("graph.rebase", copy.commandRebase, GraphOperation.Rebase::class.java, callbacks::request),
    command("graph.cherryPick", copy.commandCherryPick, GraphOperation.CherryPick::class.java, callbacks::request),
    command("graph.resetBranch", copy.commandReset, GraphOperation.ResetBranch::class.java, callbacks::request),
    command("graph.moveTag", copy.commandMoveTag, GraphOperation.MoveTag::class.java, callbacks::request),
).map { prototype ->
    val expected = prototype.expected
    Command(
        id = prototype.id,
        title = prototype.title,
        availability = {
            if (selectedOperation()?.let(expected::isInstance) == true) CommandAvailability.Available
            else CommandAvailability.Blocked(copy.unavailableCommand)
        },
        action = { prototype.action(selectedOperation() ?: return@Command) },
    )
}

private data class GraphOperationCommandPrototype(
    val id: CommandId,
    val title: String,
    val expected: Class<out GraphOperation>,
    val action: (GraphOperation) -> Unit,
)

private fun command(
    id: String,
    title: String,
    expected: Class<out GraphOperation>,
    action: (GraphOperation) -> Unit,
): GraphOperationCommandPrototype = GraphOperationCommandPrototype(CommandId(id), title, expected, action)
