package dev.undine.presentation.a11y

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.presentation.graph.GraphDragDropState
import dev.undine.presentation.graph.GraphOperationCallbacks
import dev.undine.presentation.graph.graphOperationCommands
import dev.undine.presentation.palette.CommandAvailability
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.reflect.KClass

private val MAIN = RefName("main")
private val FEATURE = RefName("feature")
private val TAG = RefName("v1.0.0")
private val COMMIT = CommitId.of("a".repeat(40))
private val HEAD = CommitId.of("b".repeat(40))

/** 티켓이 지목한 다섯 드래그 조작 — merge · rebase · cherry-pick · reset branch · move tag. */
private const val DRAG_OPERATION_COUNT = 5

/**
 * 드래그로 만들 수 있는 조작마다 **키보드로 닿는 등가 Command** 가 있는지.
 *
 * 조작 이름을 손으로 나열하면 여섯 번째 조작이 드래그 전용으로 들어와도 이 검증은 통과한다.
 * 그래서 [GraphOperation] 의 sealed 하위 타입 전부를 훑고, 표본이 없는 하위 타입이 생기면
 * **그 사실 자체로 실패**하게 한다 — 드래그 전용 조작을 만들 수 없다.
 */
private val SAMPLES: Map<KClass<out GraphOperation>, GraphOperation> = mapOf(
    GraphOperation.Merge::class to GraphOperation.Merge(FEATURE, BranchTarget.Named(MAIN)),
    GraphOperation.Rebase::class to GraphOperation.Rebase(BranchTarget.Named(FEATURE), MAIN),
    GraphOperation.CherryPick::class to GraphOperation.CherryPick(COMMIT, BranchTarget.Named(MAIN)),
    GraphOperation.ResetBranch::class to GraphOperation.ResetBranch(MAIN, COMMIT),
    GraphOperation.MoveTag::class to GraphOperation.MoveTag(TAG, COMMIT),
)

class GraphOperationKeyboardEquivalenceSpec : FunSpec({

    test("그래프 조작 하위 타입 전부가 표본을 가진다 — 새 조작이 검증을 비껴가지 않는다") {
        GraphOperation::class.sealedSubclasses.toSet() shouldBe SAMPLES.keys
        SAMPLES.keys shouldHaveSize DRAG_OPERATION_COUNT
    }

    test("드래그로 만들 수 있는 조작마다 실행 가능한 등가 Command 가 정확히 하나 있다") {
        SAMPLES.forEach { (type, operation) ->
            val callbacks = GraphOperationCallbacks(dragDropState())
            val commands = graphOperationCommands(callbacks) { operation }
            val available = commands.filter { it.availability() == CommandAvailability.Available }

            withClue(type) {
                available shouldHaveSize 1
                available.single().action()
                callbacks.lastRequested shouldContainExactly listOf(operation)
            }
        }
    }

    test("선택이 없으면 다섯 명령 모두 사유와 함께 막힌다 — 조용히 아무 일도 하지 않지 않는다") {
        val callbacks = GraphOperationCallbacks(dragDropState())
        val commands = graphOperationCommands(callbacks) { null }

        commands shouldHaveSize SAMPLES.size
        commands.forEach { command ->
            withClue(command.id.value) {
                (command.availability() is CommandAvailability.Blocked) shouldBe true
            }
        }
        // 막힌 명령을 실행해도 조작이 나가지 않는다.
        commands.forEach { it.action() }
        callbacks.lastRequested shouldContainExactly emptyList()
    }
})

private fun dragDropState(): GraphDragDropState = GraphDragDropState(
    execute = { GraphOperationOutcome.Completed(MAIN, HEAD, undoRecordFailure = null) },
    scope = CoroutineScope(Dispatchers.Unconfined),
)
