package dev.undine.presentation.submodule

import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path

class SubmoduleWorktreeStateSpec : FunSpec({
    test("수정됨과 어긋남이 동시인 서브모듈은 두 정보와 두 대응 동작을 모두 보존한다") {
        val gateway = RecordingSubmoduleGateway(
            listOf(
                Submodule(
                    "modules/core",
                    null,
                    SubmoduleState(true, locallyModified = true, divergedFromRecorded = true),
                ),
            ),
        )
        val opened = mutableListOf<String>()
        val state = SubmodulePanelState(
            actions = submoduleActions(gateway),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onOpenRequested = opened::add,
        )

        state.refresh()
        val row = state.rows.single()

        row.locallyModified shouldBe true
        row.divergedFromRecorded shouldBe true
        row.actions shouldContainExactly listOf(
            SubmoduleAction.OPEN,
            SubmoduleAction.COMMIT_TO_PARENT,
            SubmoduleAction.UPDATE_FROM_PARENT,
        )
        state.requestOpen(row)
        opened shouldContainExactly listOf("modules/core")
    }

    test("미초기화 서브모듈은 초기화만 제공하고 요청을 UseCase로 보낸다") {
        val gateway = RecordingSubmoduleGateway(
            listOf(Submodule("modules/empty", null, SubmoduleState(false, false, false))),
        )
        val state = submoduleState(gateway)
        state.refresh()

        val row = state.rows.single()
        row.actions shouldContainExactly listOf(SubmoduleAction.INITIALIZE)
        state.initialize(row)
        gateway.initialized shouldContainExactly listOf("modules/empty")
    }

    test("초기화·업데이트 성공은 Undo 스택에 종류로 기록된다") {
        val gateway = RecordingSubmoduleGateway(
            listOf(Submodule("modules/core", null, SubmoduleState(true, true, true))),
        )
        val stack = UndoStack()
        val state = SubmodulePanelState(
            actions = submoduleActions(gateway, stack),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        state.refresh()

        val row = state.rows.single()
        state.initialize(row)
        state.updateFromParent(row)

        stack.history().map { it.operation } shouldContainExactly listOf(
            GitOperationKind.SUBMODULE_UPDATE,
            GitOperationKind.SUBMODULE_INIT,
        )
    }

    test("목록이 0건이면 빈 상태를 보이고 Gateway 실패는 빈 목록으로 바꾸지 않는다") {
        val emptyState = submoduleState(RecordingSubmoduleGateway(emptyList()))
        emptyState.refresh()
        emptyState.isEmpty shouldBe true

        val failed = submoduleState(
            RecordingSubmoduleGateway(failure = UndineException.GitOperationFailed("submodule.list")),
        )
        failed.refresh()
        failed.failure.shouldBeInstanceOf<UndineException.GitOperationFailed>()
        failed.isEmpty shouldBe false
    }

    test("NotFound·StateViolation 도 종류를 잃지 않고 실패 상태로 남는다") {
        val notFound = submoduleState(
            RecordingSubmoduleGateway(
                failure = UndineException.NotFound(UndineException.NotFound.Kind.SUBMODULE, "modules/core"),
            ),
        )
        notFound.refresh()
        notFound.failure.shouldBeInstanceOf<UndineException.NotFound>()

        val violation = submoduleState(
            RecordingSubmoduleGateway(failure = UndineException.StateViolation("서브모듈이 초기화되지 않았습니다")),
        )
        violation.refresh()
        violation.failure.shouldBeInstanceOf<UndineException.StateViolation>()
    }

    test("취소는 실패로 바뀌지 않고 뒤따르는 갱신도 하지 않는다") {
        val gateway = RecordingSubmoduleGateway(
            listOf(Submodule("modules/core", null, SubmoduleState(false, false, false))),
            initializeFailure = CancellationException("화면이 사라졌습니다"),
        )
        val state = submoduleState(gateway)
        state.refresh()
        gateway.listCount shouldBe 1

        state.initialize(state.rows.single())

        state.failure.shouldBeNull()
        gateway.listCount shouldBe 1
    }

    test("진행 중인 서브모듈 초기화·업데이트 재요청은 한 번만 실행하고 기록한다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = RecordingSubmoduleGateway(
            listOf(Submodule("modules/core", null, SubmoduleState(false, false, false))),
            gate = gate,
        )
        val stack = UndoStack()
        val state = SubmodulePanelState(
            actions = submoduleActions(gateway, stack),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        state.refresh()

        val row = state.rows.single()
        state.initialize(row)
        state.busy shouldBe true
        state.initialize(row)
        state.updateFromParent(row)
        gate.complete(Unit)

        gateway.initialized shouldContainExactly listOf("modules/core")
        gateway.updated.shouldBeEmpty()
        stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.SUBMODULE_INIT)
        state.busy shouldBe false
    }

    test("현재 worktree는 정규화한 경로가 같을 때만 구분하고 다른 worktree 열기는 콜백으로 요청한다") {
        val tempDirectory = Path.of(System.getProperty("java.io.tmpdir"))
            .toRealPath()
            .toString()
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(
                listOf(
                    Worktree(
                        "main",
                        RepositoryPath("$tempDirectory/."),
                        RefName("main"),
                        WorktreeState.MAIN,
                    ),
                    Worktree(
                        "feature",
                        RepositoryPath("$tempDirectory/feature"),
                        RefName("feature"),
                        WorktreeState.LINKED,
                    ),
                ),
                emptyList(),
            ),
        )
        val opened = mutableListOf<RepositoryPath>()
        val state = WorktreePanelState(
            actions = worktreeActions(gateway),
            currentWorktree = RepositoryPath(tempDirectory),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onOpenRequested = opened::add,
            ioDispatcher = Dispatchers.Unconfined,
        )

        state.refresh()

        state.rows.map { it.isCurrent } shouldBe listOf(true, false)
        state.requestOpen(state.rows[1])
        opened shouldContainExactly listOf(RepositoryPath("$tempDirectory/feature"))
    }

    test("더티 제거는 파일 수 경고로 남고 성공이나 강제 재시도를 만들지 않는다") {
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(
                listOf(Worktree("feature", RepositoryPath("/tmp/feature"), null, WorktreeState.LINKED)),
                emptyList(),
            ),
            removeFailure = UndineException.DirtyWorkingTree(listOf("a.kt", "b.kt")),
        )
        val state = worktreeState(gateway)
        state.refresh()

        state.remove(state.rows.single())

        state.dirtyRemovalPathCount shouldBe 2
        state.lastRemovedName shouldBe null
        gateway.removeRequests shouldContainExactly listOf("feature")
    }

    test("고아 worktree에만 prune을 노출하고 prune도 기존 remove 경로를 쓴다") {
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(
                listOf(
                    Worktree("orphan", RepositoryPath("/tmp/missing"), null, WorktreeState.ORPHANED),
                    Worktree("linked", RepositoryPath("/tmp/linked"), null, WorktreeState.LINKED),
                ),
                emptyList(),
            ),
        )
        val state = worktreeState(gateway)
        state.refresh()

        state.rows[0].actions shouldContainExactly listOf(WorktreeAction.PRUNE)
        state.rows[1].actions shouldContainExactly listOf(WorktreeAction.OPEN, WorktreeAction.REMOVE)
        state.prune(state.rows[0])
        gateway.removeRequests shouldContainExactly listOf("orphan")
        state.lastRemovedName shouldBe "orphan"
    }

    test("빈 입력은 추가를 요청하지 않고, 성공하면 입력을 비운 뒤 목록을 다시 읽는다") {
        val gateway = RecordingWorktreeGateway(WorktreeListing(emptyList(), emptyList()))
        val state = worktreeState(gateway)

        state.canSubmitAdd shouldBe false
        state.submitAdd()
        gateway.addRequests.shouldBeEmpty()

        state.updateDraftPath("/tmp/feature")
        state.updateDraftBranch("feature")
        state.canSubmitAdd shouldBe true
        state.submitAdd()

        gateway.addRequests shouldContainExactly listOf(RepositoryPath("/tmp/feature") to RefName("feature"))
        state.draftPath shouldBe ""
        state.draftBranch shouldBe ""
    }

    test("추가가 실패하면 사용자가 친 입력을 지우지 않고 실패를 남긴다") {
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(emptyList(), emptyList()),
            addFailure = UndineException.StateViolation("이미 있는 경로입니다"),
        )
        val state = worktreeState(gateway)

        state.updateDraftPath("/tmp/feature")
        state.updateDraftBranch("feature")
        state.submitAdd()

        state.failure.shouldBeInstanceOf<UndineException.StateViolation>()
        state.draftPath shouldBe "/tmp/feature"
        state.draftBranch shouldBe "feature"
    }

    test("진행 중인 worktree 추가 재요청은 한 번만 실행하고 기록한다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = RecordingWorktreeGateway(WorktreeListing(emptyList(), emptyList()), gate = gate)
        val stack = UndoStack()
        val state = WorktreePanelState(
            actions = worktreeActions(gateway, stack),
            currentWorktree = RepositoryPath("/tmp/current"),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        state.updateDraftPath("/tmp/feature")
        state.updateDraftBranch("feature")

        state.submitAdd()
        state.busy shouldBe true
        state.submitAdd()
        gate.complete(Unit)

        gateway.addRequests shouldContainExactly listOf(RepositoryPath("/tmp/feature") to RefName("feature"))
        stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_ADD)
        state.busy shouldBe false
    }

    test("진행 중인 worktree 제거·prune 재요청은 한 번만 실행하고 기록한다") {
        val gate = CompletableDeferred<Unit>()
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(
                listOf(
                    Worktree("linked", RepositoryPath("/tmp/linked"), null, WorktreeState.LINKED),
                    Worktree("orphan", RepositoryPath("/tmp/orphan"), null, WorktreeState.ORPHANED),
                ),
                emptyList(),
            ),
            gate = gate,
        )
        val stack = UndoStack()
        val state = WorktreePanelState(
            actions = worktreeActions(gateway, stack),
            currentWorktree = RepositoryPath("/tmp/current"),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        state.refresh()

        state.remove(state.rows[0])
        state.busy shouldBe true
        state.remove(state.rows[0])
        state.prune(state.rows[1])
        gate.complete(Unit)

        gateway.removeRequests shouldContainExactly listOf("linked")
        stack.history().map { it.operation } shouldContainExactly listOf(GitOperationKind.WORKTREE_REMOVE)
        state.busy shouldBe false
    }

    test("worktree 추가·제거 성공은 Undo 스택에 종류로 기록된다") {
        val gateway = RecordingWorktreeGateway(
            WorktreeListing(
                listOf(Worktree("feature", RepositoryPath("/tmp/feature"), null, WorktreeState.LINKED)),
                emptyList(),
            ),
        )
        val stack = UndoStack()
        val state = WorktreePanelState(
            actions = worktreeActions(gateway, stack),
            currentWorktree = RepositoryPath("/tmp/current"),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        state.refresh()

        state.updateDraftPath("/tmp/other")
        state.updateDraftBranch("other")
        state.submitAdd()
        state.remove(state.rows.single())

        stack.history().map { it.operation } shouldContainExactly listOf(
            GitOperationKind.WORKTREE_REMOVE,
            GitOperationKind.WORKTREE_ADD,
        )
    }
})

private val HEAD = CommitId.of("c".repeat(40))

private fun recorderOn(stack: UndoStack): OperationRecorder {
    val refGateway = mockk<RefGateway>()
    coEvery { refGateway.listBranches() } returns listOf(
        Branch(RefName("main"), HEAD, isCurrent = true, isRemote = false, upstream = null, ahead = 0, behind = 0),
    )
    return OperationRecorder(refGateway, stack)
}

private fun submoduleActions(gateway: RecordingSubmoduleGateway, stack: UndoStack = UndoStack()) =
    SubmodulePanelActions(
        load = LoadSubmodulesUseCase(gateway),
        initialize = InitializeSubmoduleUseCase(gateway, recorderOn(stack)),
        update = UpdateSubmoduleUseCase(gateway, recorderOn(stack)),
    )

private fun worktreeActions(gateway: RecordingWorktreeGateway, stack: UndoStack = UndoStack()) =
    WorktreePanelActions(
        load = LoadWorktreesUseCase(gateway),
        add = AddWorktreeUseCase(gateway, recorderOn(stack)),
        remove = RemoveWorktreeUseCase(gateway, recorderOn(stack)),
    )

private fun submoduleState(gateway: RecordingSubmoduleGateway) =
    SubmodulePanelState(submoduleActions(gateway), CoroutineScope(Dispatchers.Unconfined))

private fun worktreeState(gateway: RecordingWorktreeGateway) =
    WorktreePanelState(
        actions = worktreeActions(gateway),
        currentWorktree = RepositoryPath("/tmp/current"),
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

private class RecordingSubmoduleGateway(
    private val entries: List<Submodule> = emptyList(),
    private val failure: UndineException? = null,
    private val initializeFailure: Throwable? = null,
    /** 완료될 때까지 변경 연산을 붙잡아 둔다 — 진행 중 재요청을 재현하는 용도다. */
    private val gate: CompletableDeferred<Unit>? = null,
) : SubmoduleGateway {
    val initialized = mutableListOf<String>()
    val updated = mutableListOf<String>()
    var listCount: Int = 0
        private set

    override suspend fun list(): List<Submodule> {
        failure?.let { throw it }
        listCount++
        return entries
    }

    override suspend fun initialize(path: String, recursive: Boolean) {
        gate?.await()
        initializeFailure?.let { throw it }
        initialized += path
    }

    override suspend fun update(path: String, recursive: Boolean) {
        gate?.await()
        updated += path
    }
    override suspend fun add(url: String, path: String, branch: String?): Submodule = error("사용하지 않는다")
    override suspend fun remove(path: String, confirmed: Boolean) = error("사용하지 않는다")
}

private class RecordingWorktreeGateway(
    private val listing: WorktreeListing,
    private val removeFailure: UndineException? = null,
    private val addFailure: UndineException? = null,
    /** 완료될 때까지 변경 연산을 붙잡아 둔다 — 진행 중 재요청을 재현하는 용도다. */
    private val gate: CompletableDeferred<Unit>? = null,
) : WorktreeGateway {
    val removeRequests = mutableListOf<String>()
    val addRequests = mutableListOf<Pair<RepositoryPath, RefName>>()

    override suspend fun list(): WorktreeListing = listing

    override suspend fun add(path: RepositoryPath, branch: RefName): Worktree {
        gate?.await()
        addFailure?.let { throw it }
        addRequests += path to branch
        return Worktree(path.value, path, branch, WorktreeState.LINKED)
    }

    override suspend fun remove(name: String) {
        gate?.await()
        removeRequests += name
        removeFailure?.let { throw it }
    }
}
