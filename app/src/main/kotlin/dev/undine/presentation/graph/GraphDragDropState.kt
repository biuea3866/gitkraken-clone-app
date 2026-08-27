package dev.undine.presentation.graph

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.undine.application.graphops.ExecuteGraphOperationUseCase
import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.BranchTarget
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import dev.undine.domain.graphops.GraphDragSource
import dev.undine.domain.graphops.GraphDropProposal
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphDropTarget
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.graphops.proposeGraphDrop
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.GraphDragDropStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.graphDragDrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val SHORT_COMMIT_LENGTH = 7

/** 드래그 중 드롭 대상 위에 보여 줄 순수 표시 상태. */
data class GraphDropPreview(
    val canDrop: Boolean,
    val message: String,
    val refusal: GraphDropRefusal? = null,
)

/** 실행 직전의 확인 상태. 둘 이상의 선택지가 있으면 먼저 [choose] 해야 한다. */
data class GraphOperationConfirmation(
    val choices: List<GraphOperation>,
    val selected: GraphOperation? = null,
    val warning: String? = null,
) {
    val isDestructive: Boolean get() = selected?.isDestructive == true
}

/** 그래프 조작 완료 뒤 화면에 남길 결과. 충돌은 실패가 아니라 별도 상태다. */
sealed interface GraphDragDropUiOutcome {
    /**
     * @property undoRecordFailure null 이 아니면 **저장소 변경은 성공했고 Undo 항목만 남지 않았다.**
     *   버리면 사용자는 되돌릴 수 있다고 믿는다 — 화면이 복구 불가 사실과 reflog 경로를 알려야 한다.
     */
    data class Completed(
        val ref: RefName,
        val undoRecordFailure: UndineException? = null,
    ) : GraphDragDropUiOutcome
    data class Conflicted(val ref: RefName, val paths: List<String>) : GraphDragDropUiOutcome
    data class NoChange(val ref: RefName) : GraphDragDropUiOutcome
    data class Failed(val failure: UndineException) : GraphDragDropUiOutcome
}

/**
 * 그래프 드래그·컨텍스트 메뉴·팔레트가 공유하는 상태 홀더.
 *
 * 드롭은 [confirmation] 만 연다. 저장소 변경은 [confirm] 뒤에만 시작하므로 ESC/취소 경로가
 * UseCase까지 내려갈 수 없다. Composable은 이 상태를 읽어 미리보기·확인창·결과를 그릴 뿐이다.
 */
@Stable
@Suppress("TooManyFunctions") // 한 화면의 드래그·확인·실행 전이를 같은 홀더에서 닫는다.
class GraphDragDropState(
    private val execute: suspend (GraphOperation) -> GraphOperationOutcome,
    private val scope: CoroutineScope,
    private val copy: GraphDragDropStrings = builtInStringCatalog().stringsFor(DEFAULT_LOCALE).graphDragDrop,
) {
    // 드롭 대상의 활성/비활성 표시가 이 값으로 갈리므로 관찰 가능해야 한다 — 평범한 var 면
    // 드래그를 시작해도 이미 그려진 행·칩이 다시 그려지지 않는다.
    private var dragSource: GraphDragSource? by mutableStateOf(null)

    var preview: GraphDropPreview? by mutableStateOf(null)
        private set

    var confirmation: GraphOperationConfirmation? by mutableStateOf(null)
        private set

    var outcome: GraphDragDropUiOutcome? by mutableStateOf(null)
        private set

    var executing: Boolean by mutableStateOf(false)
        private set

    val isDragging: Boolean get() = dragSource != null

    fun beginDrag(source: GraphDragSource) {
        dragSource = source
        preview = null
        confirmation = null
    }

    /**
     * 드래그 중 이 대상이 드롭을 받을 수 없는 사유. 끌고 있는 것이 없거나 놓을 수 있으면 `null` 이다.
     *
     * 행·칩은 이 값으로 **비활성으로 그린다** — 불가 조합을 활성처럼 보여 주면 사용자는 놓고 나서야
     * 거부 문구를 본다 (AC1). 판정은 [proposeGraphDrop] 과 같은 순수 함수라 매 프레임 물어도 된다.
     */
    fun refusalFor(target: GraphDropTarget): GraphDropRefusal? {
        val source = dragSource ?: return null
        return (proposeGraphDrop(source, target) as? GraphDropProposal.Unavailable)?.reason
    }

    /**
     * 드래그 세션이 끝났다 — 유효한 대상에 놓았든, 대상 밖에 놓았든, 플랫폼이 취소했든.
     *
     * 끌던 것을 여기서 지우지 않으면 이전 source 가 다음 드래그까지 남아, 그 사이의 드롭이
     * **이전 조작의 확인 경로로 들어간다.**
     *
     * 지우는 것은 끌던 것뿐이다. 열려 있는 확인창은 드래그가 끝난 뒤의 단계이므로 건드리지 않고,
     * 마지막 미리보기도 남긴다 — 놓을 수 없는 대상에 놓았을 때의 거부 문구가 여기서 사라지면
     * 사용자는 왜 아무 일도 안 일어났는지 알 수 없다.
     */
    fun endDrag() {
        dragSource = null
    }

    fun hover(target: GraphDropTarget) {
        val source = dragSource ?: return
        preview = previewOf(proposeGraphDrop(source, target))
    }

    /** 불가 대상은 확인·실행 경로로 넣지 않는다. */
    fun drop(target: GraphDropTarget) {
        val source = dragSource ?: return
        val proposal = proposeGraphDrop(source, target)
        preview = previewOf(proposal)
        if (proposal is GraphDropProposal.Available) {
            openConfirmation(proposal.choices)
        }
    }

    /** 컨텍스트 메뉴·팔레트의 키보드 등가 경로. 역시 확인 없이는 실행하지 않는다. */
    fun request(operation: GraphOperation) {
        openConfirmation(listOf(operation))
    }

    fun choose(operation: GraphOperation) {
        val pending = confirmation ?: return
        if (operation !in pending.choices) return
        confirmation = pending.copy(
            selected = operation,
            warning = if (operation.isDestructive) copy.destructiveWarning else null,
        )
    }

    fun cancelConfirmation() {
        confirmation = null
        dragSource = null
    }

    fun onEscape() = cancelConfirmation()

    /** 확인된 한 조작만 실행한다. 같은 확인창을 두 번 눌러도 두 번 시작하지 않는다. */
    fun confirm() {
        val operation = confirmation?.selected ?: return
        if (executing) return
        executing = true
        confirmation = null
        dragSource = null
        scope.launch {
            try {
                outcome = execute(operation).toUiOutcome()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: UndineException) {
                outcome = GraphDragDropUiOutcome.Failed(failure)
            } finally {
                executing = false
            }
        }
    }

    private fun openConfirmation(choices: List<GraphOperation>) {
        val selected = choices.singleOrNull()
        confirmation = GraphOperationConfirmation(
            choices = choices,
            selected = selected,
            warning = if (selected?.isDestructive == true) copy.destructiveWarning else null,
        )
    }

    private fun previewOf(proposal: GraphDropProposal): GraphDropPreview = when (proposal) {
        is GraphDropProposal.Available -> GraphDropPreview(canDrop = true, message = proposalMessage(proposal.choices))
        is GraphDropProposal.Unavailable -> GraphDropPreview(
            canDrop = false,
            message = refusalMessage(proposal.reason),
            refusal = proposal.reason,
        )
    }

    private fun proposalMessage(choices: List<GraphOperation>): String =
        if (choices.size == 2) {
            val merge = choices.filterIsInstance<GraphOperation.Merge>().single()
            copy.mergeOrRebase(merge.source.value, merge.into.label())
        } else {
            operationMessage(choices.single())
        }

    private fun operationMessage(operation: GraphOperation): String = when (operation) {
        is GraphOperation.Merge -> copy.merge(operation.source.value, operation.into.label())
        is GraphOperation.Rebase -> copy.rebase(operation.branch.label(), operation.upstream.value)
        is GraphOperation.CherryPick -> copy.cherryPick(operation.commit.short(), operation.onto.label())
        is GraphOperation.ResetBranch -> copy.reset(operation.branch.value, operation.to.short())
        is GraphOperation.MoveTag -> copy.moveTag(operation.tag.value, operation.to.short())
    }

    private fun refusalMessage(refusal: GraphDropRefusal): String = when (refusal) {
        GraphDropRefusal.SAME_REF -> copy.sameRef
        GraphDropRefusal.SAME_COMMIT -> copy.sameCommit
        GraphDropRefusal.ANNOTATED_TAG -> copy.annotatedTag
        GraphDropRefusal.UNSUPPORTED_COMBINATION -> copy.unsupported
    }

    private fun GraphOperationOutcome.toUiOutcome(): GraphDragDropUiOutcome = when (this) {
        is GraphOperationOutcome.Completed -> GraphDragDropUiOutcome.Completed(ref, undoRecordFailure)
        is GraphOperationOutcome.Conflicted -> GraphDragDropUiOutcome.Conflicted(ref, paths)
        is GraphOperationOutcome.NoChange -> GraphDragDropUiOutcome.NoChange(ref)
    }

    private fun BranchTarget.label(): String = when (this) {
        BranchTarget.Current -> copy.currentBranch
        is BranchTarget.Named -> branch.value
    }

    private fun dev.undine.domain.CommitId.short(): String = value.take(SHORT_COMMIT_LENGTH)

    companion object {
        fun from(useCase: ExecuteGraphOperationUseCase, scope: CoroutineScope): GraphDragDropState =
            GraphDragDropState(execute = useCase::execute, scope = scope)
    }
}
