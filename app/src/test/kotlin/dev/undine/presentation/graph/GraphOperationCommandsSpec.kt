package dev.undine.presentation.graph

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.contextmenu.GraphContextSelection
import dev.undine.presentation.contextmenu.GraphContextTarget
import dev.undine.presentation.contextmenu.GraphMenuEntry
import dev.undine.presentation.contextmenu.graphMenuEntriesFor
import dev.undine.presentation.palette.CommandAvailability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    test("팔레트용 명령 정의는 등록하지 않고 산출된 조작에 해당하는 명령만 활성화한다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val commands = graphOperationCommands(callbacks) {
            listOf(entryOf(GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Named(COMMAND_MAIN))))
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

    /**
     * 예전 계약은 대상 하나에서 조작 **하나**만 나온다는 전제였다. 브랜치를 지목하면 병합과
     * 리베이스가 동시에 가능하므로 그 전제로는 둘 중 하나가 언제나 막힌다 (결정 D10).
     */
    test("브랜치를 지목하면 merge 와 rebase 가 함께 활성화된다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val branchEntries = graphMenuEntriesFor(
            target = GraphContextTarget.Branch(COMMAND_FEATURE, COMMAND_HEAD, isRemote = false),
            selection = GraphContextSelection(commit = COMMAND_COMMIT, currentBranch = COMMAND_MAIN),
        )
        val commands = graphOperationCommands(callbacks) { branchEntries }

        val available = commands.filter { it.availability() == CommandAvailability.Available }
        available.map { it.id.value } shouldContainExactly listOf(
            "graph.merge",
            "graph.rebase",
            "graph.resetBranch",
        )
        commands.single { it.id.value == "graph.cherryPick" }.availability()
            .shouldBeInstanceOf<CommandAvailability.Blocked>()
    }

    /** 커밋 선택은 예전과 같다 — cherry-pick 하나만 산출된다. */
    test("커밋을 지목하면 cherry-pick 만 활성화된다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val commitEntries = graphMenuEntriesFor(
            target = GraphContextTarget.Commit(COMMAND_COMMIT),
            selection = GraphContextSelection(commit = COMMAND_COMMIT, currentBranch = COMMAND_MAIN),
        )
        val commands = graphOperationCommands(callbacks) { commitEntries }

        commands.filter { it.availability() == CommandAvailability.Available }
            .map { it.id.value } shouldContainExactly listOf("graph.cherryPick")

        commands.single { it.id.value == "graph.cherryPick" }.action()
        callbacks.lastRequested shouldContainExactly listOf(
            GraphOperation.CherryPick(COMMAND_COMMIT, BranchTarget.Current),
        )
    }

    test("팔레트 명령은 현재 브랜치 대상을 이름 스냅샷으로 바꾸지 않는다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val operation = GraphOperation.Merge(COMMAND_FEATURE, BranchTarget.Current)

        graphOperationCommands(callbacks) { listOf(entryOf(operation)) }.first().action()

        callbacks.lastRequested shouldContainExactly listOf(operation)
    }

    /**
     * 1차 수리가 detached HEAD 가드를 컨텍스트 메뉴에만 넣어 팔레트가 같은 제약을 우회했다.
     * 팔레트는 조작 목록이 아니라 **가용성까지 담은 항목**을 받으므로 이제 우회할 수 없다 (결정 D17).
     */
    test("detached HEAD 면 현재 브랜치 조작 명령이 사유와 함께 막히고 눌러도 요청이 나가지 않는다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(COMMAND_MAIN, COMMAND_HEAD, undoRecordFailure = null) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val callbacks = GraphOperationCallbacks(state)
        val detachedEntries = graphMenuEntriesFor(
            target = GraphContextTarget.Branch(COMMAND_FEATURE, COMMAND_HEAD, isRemote = false),
            selection = GraphContextSelection(commit = COMMAND_COMMIT, currentBranch = null),
        )
        val commands = graphOperationCommands(callbacks) { detachedEntries }

        commands.single { it.id.value == "graph.merge" }.availability() shouldBe
            CommandAvailability.Blocked("체크아웃된 브랜치가 없습니다(detached HEAD). 브랜치를 먼저 체크아웃하세요")
        commands.single { it.id.value == "graph.rebase" }.availability() shouldBe
            CommandAvailability.Blocked("체크아웃된 브랜치가 없습니다(detached HEAD). 브랜치를 먼저 체크아웃하세요")
        // 이름으로 지목한 브랜치를 옮기는 reset 은 detached 여도 성립한다 — 막으면 할 수 있는 일을 막는다.
        commands.single { it.id.value == "graph.resetBranch" }.availability() shouldBe CommandAvailability.Available

        commands.forEach { it.action() }

        callbacks.lastRequested shouldContainExactly listOf(
            GraphOperation.ResetBranch(COMMAND_FEATURE, COMMAND_COMMIT),
        )
    }
})

/** 막히지 않은 항목 — 팔레트가 조작 하나만 두고 판정을 요구하지 않는 경우에 쓴다. */
private fun entryOf(operation: GraphOperation): GraphMenuEntry = GraphMenuEntry(operation, blockedReason = null)
