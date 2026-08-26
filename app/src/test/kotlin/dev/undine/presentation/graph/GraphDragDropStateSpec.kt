package dev.undine.presentation.graph

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphDropTarget
import dev.undine.domain.graphops.GraphOperation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val RELEASE = RefName("v1.0.0")
private val MAIN_HEAD = CommitId.of("a".repeat(40))
private val FEATURE_HEAD = CommitId.of("b".repeat(40))
private val COMMIT = CommitId.of("c".repeat(40))

private fun stateWith(executed: MutableList<GraphOperation>): GraphDragDropState =
    GraphDragDropState(
        execute = { operation ->
            executed += operation
            GraphOperationOutcome.Completed(MAIN, MAIN_HEAD, undoRecordFailure = null)
        },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

class GraphDragDropStateSpec : FunSpec({

    test("지원 조합은 결과 문장과 드롭 가능 상태를 미리 보여준다") {
        val state = stateWith(mutableListOf())

        state.beginDrag(GraphDragSource.Branch(FEATURE, FEATURE_HEAD))
        state.hover(GraphDropTarget.Branch(MAIN, MAIN_HEAD))

        state.preview?.canDrop shouldBe true
        state.preview?.message shouldBe "feature 를 main 에 병합하거나 리베이스합니다"
    }

    test("지원하지 않는 조합은 비활성이고 드롭 실행 경로로 들어가지 않는다") {
        val executed = mutableListOf<GraphOperation>()
        val state = stateWith(executed)

        state.beginDrag(GraphDragSource.Tag(RELEASE, COMMIT, isAnnotated = false))
        state.drop(GraphDropTarget.Branch(MAIN, MAIN_HEAD))

        state.preview?.canDrop shouldBe false
        state.confirmation.shouldBeNull()
        executed.shouldBeEmpty()
    }

    test("드롭은 먼저 병합 또는 리베이스 선택 확인을 열고 확인 전에는 실행하지 않는다") {
        val executed = mutableListOf<GraphOperation>()
        val state = stateWith(executed)

        state.beginDrag(GraphDragSource.Branch(FEATURE, FEATURE_HEAD))
        state.drop(GraphDropTarget.Branch(MAIN, MAIN_HEAD))

        state.confirmation?.choices shouldContainExactly listOf(
            GraphOperation.Merge(FEATURE, BranchTarget.Named(MAIN)),
            GraphOperation.Rebase(BranchTarget.Named(FEATURE), MAIN),
        )
        executed.shouldBeEmpty()

        state.choose(GraphOperation.Rebase(BranchTarget.Named(FEATURE), MAIN))
        state.confirm()

        executed shouldContainExactly listOf(GraphOperation.Rebase(BranchTarget.Named(FEATURE), MAIN))
    }

    test("취소와 ESC 취소는 Gateway UseCase 실행을 만들지 않는다") {
        val executed = mutableListOf<GraphOperation>()
        val state = stateWith(executed)

        state.request(GraphOperation.CherryPick(COMMIT, BranchTarget.Named(MAIN)))
        state.cancelConfirmation()
        state.request(GraphOperation.ResetBranch(MAIN, COMMIT))
        state.onEscape()

        state.confirmation.shouldBeNull()
        executed.shouldBeEmpty()
    }

    test("reset 확인은 위험 경고를 남긴다") {
        val state = stateWith(mutableListOf())

        state.request(GraphOperation.ResetBranch(MAIN, COMMIT))

        state.confirmation?.isDestructive shouldBe true
        state.confirmation?.warning shouldBe "브랜치와 워킹 트리의 커밋하지 않은 변경이 사라질 수 있습니다"
    }

    test("드래그 중 놓을 수 없는 대상은 사유와 함께 비활성으로 표시된다") {
        val state = stateWith(mutableListOf())

        state.refusalFor(GraphDropTarget.Branch(MAIN, MAIN_HEAD)).shouldBeNull()

        state.beginDrag(GraphDragSource.Tag(RELEASE, COMMIT, isAnnotated = true))

        // 같은 태그를 다른 커밋에 놓아도 annotated 라 옮길 수 없다 — 놓기 전에 그 사실이 보여야 한다.
        state.refusalFor(GraphDropTarget.Commit(MAIN_HEAD)) shouldBe GraphDropRefusal.ANNOTATED_TAG
        state.refusalFor(GraphDropTarget.Branch(MAIN, MAIN_HEAD)) shouldBe
            GraphDropRefusal.UNSUPPORTED_COMBINATION
    }

    test("드래그를 끝내면 비활성 표시도 사라진다") {
        val state = stateWith(mutableListOf())

        state.beginDrag(GraphDragSource.Tag(RELEASE, COMMIT, isAnnotated = true))
        state.cancelConfirmation()

        state.refusalFor(GraphDropTarget.Commit(MAIN_HEAD)).shouldBeNull()
    }

    test("대상 밖에 놓아 드래그가 끝나면 다음 드롭이 이전 조작으로 해석되지 않는다") {
        val executed = mutableListOf<GraphOperation>()
        val state = stateWith(executed)

        state.beginDrag(GraphDragSource.Branch(FEATURE, FEATURE_HEAD))
        state.endDrag()
        state.drop(GraphDropTarget.Branch(MAIN, MAIN_HEAD))

        state.confirmation.shouldBeNull()
        executed.shouldBeEmpty()
    }

    test("드래그가 끝나도 놓았을 때의 거부 문구는 남는다") {
        val state = stateWith(mutableListOf())

        state.beginDrag(GraphDragSource.Tag(RELEASE, COMMIT, isAnnotated = true))
        state.drop(GraphDropTarget.Commit(MAIN_HEAD))
        state.endDrag()

        state.preview?.canDrop shouldBe false
    }

    test("변경은 됐고 Undo 기록만 실패한 경우 그 사실이 결과에 남는다") {
        val recordFailure = UndineException.StateViolation("undo stack unavailable")
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Completed(MAIN, MAIN_HEAD, undoRecordFailure = recordFailure) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        state.request(GraphOperation.ResetBranch(MAIN, COMMIT))
        state.confirm()

        state.outcome shouldBe GraphDragDropUiOutcome.Completed(MAIN, recordFailure)
    }

    test("Undo 기록까지 성공한 경우에는 복구 불가 경고를 남기지 않는다") {
        val state = stateWith(mutableListOf())

        state.request(GraphOperation.ResetBranch(MAIN, COMMIT))
        state.confirm()

        (state.outcome as GraphDragDropUiOutcome.Completed).undoRecordFailure.shouldBeNull()
    }

    test("충돌은 실패가 아니라 해결 경로가 있는 진행 중 상태로 표시한다") {
        val state = GraphDragDropState(
            execute = { GraphOperationOutcome.Conflicted(MAIN, listOf("shared.kt")) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        state.request(GraphOperation.CherryPick(COMMIT, BranchTarget.Named(MAIN)))
        state.confirm()

        state.outcome shouldBe GraphDragDropUiOutcome.Conflicted(MAIN, listOf("shared.kt"))
    }
})
