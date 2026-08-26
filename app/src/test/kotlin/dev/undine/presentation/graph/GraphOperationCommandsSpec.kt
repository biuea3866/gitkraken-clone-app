package dev.undine.presentation.graph

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.palette.CommandAvailability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val COMMAND_MAIN = RefName("main")
private val COMMAND_FEATURE = RefName("feature")
private val COMMAND_TAG = RefName("v1.0.0")
private val COMMAND_COMMIT = CommitId.of("d".repeat(40))
private val COMMAND_HEAD = CommitId.of("e".repeat(40))

class GraphOperationCommandsSpec : FunSpec({

    test("컨텍스트 메뉴와 팔레트의 다섯 명령은 같은 확인 상태 홀더 요청으로 연결된다") {
        val requested = mutableListOf<GraphOperation>()
        val state = GraphDragDropState(
            execute = { operation ->
                requested += operation
                GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null)
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)

        callbacks.merge(COMMAND_FEATURE, COMMAND_MAIN)
        callbacks.rebase(COMMAND_FEATURE, COMMAND_MAIN)
        callbacks.cherryPick(COMMAND_COMMIT, COMMAND_MAIN)
        callbacks.reset(COMMAND_MAIN, COMMAND_COMMIT)
        callbacks.moveTag(COMMAND_TAG, COMMAND_COMMIT)

        requested.shouldBeEmpty()
        callbacks.lastRequested shouldContainExactly listOf(
            GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Named(COMMAND_MAIN)),
            GraphOperation.Rebase(BranchTarget.Named(COMMAND_FEATURE), COMMAND_MAIN),
            GraphOperation.CherryPick(COMMAND_COMMIT, BranchTarget.Named(COMMAND_MAIN)),
            GraphOperation.ResetBranch(COMMAND_MAIN, COMMAND_COMMIT),
            GraphOperation.MoveTag(COMMAND_TAG, COMMAND_COMMIT),
        )
    }

    test("팔레트용 명령 정의는 등록하지 않고 현재 선택의 다섯 콜백을 제공한다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val commands = graphOperationCommands(callbacks) {
            GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Named(COMMAND_MAIN))
        }

        commands.map { it.id.value } shouldContainExactly listOf(
            "graph.merge",
            "graph.rebase",
            "graph.cherryPick",
            "graph.resetBranch",
            "graph.moveTag",
        )
        commands.first().availability() shouldBe CommandAvailability.Available
        commands.drop(1).forEach { it.availability() shouldBe CommandAvailability.Blocked("선택한 그래프 항목으로는 실행할 수 없습니다") }

        commands.first().action()

        callbacks.lastRequested shouldContainExactly listOf(
            GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Named(COMMAND_MAIN)),
        )
    }

    test("팔레트 명령은 현재 브랜치 대상을 이름 스냅샷으로 바꾸지 않는다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val operation = GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Current)

        graphOperationCommands(callbacks) { operation }.first().action()

        callbacks.lastRequested shouldContainExactly listOf(operation)
    }
})
