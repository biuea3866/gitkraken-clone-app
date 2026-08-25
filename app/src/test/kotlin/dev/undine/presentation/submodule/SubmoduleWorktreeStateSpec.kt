package dev.undine.presentation.submodule

import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
            actions = SubmodulePanelActions(
                load = LoadSubmodulesUseCase(gateway),
                initialize = InitializeSubmoduleUseCase(gateway),
                update = UpdateSubmoduleUseCase(gateway),
            ),
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
            actions = WorktreePanelActions(
                LoadWorktreesUseCase(gateway),
                AddWorktreeUseCase(gateway),
                RemoveWorktreeUseCase(gateway),
            ),
            currentWorktree = RepositoryPath(tempDirectory),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onOpenRequested = opened::add,
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
})

private fun submoduleState(gateway: RecordingSubmoduleGateway) =
    SubmodulePanelState(
        SubmodulePanelActions(
            LoadSubmodulesUseCase(gateway),
            InitializeSubmoduleUseCase(gateway),
            UpdateSubmoduleUseCase(gateway),
        ),
        CoroutineScope(Dispatchers.Unconfined),
    )

private fun worktreeState(gateway: RecordingWorktreeGateway) =
    WorktreePanelState(
        WorktreePanelActions(
            LoadWorktreesUseCase(gateway),
            AddWorktreeUseCase(gateway),
            RemoveWorktreeUseCase(gateway),
        ),
        RepositoryPath("/tmp/current"),
        CoroutineScope(Dispatchers.Unconfined),
    )

private class RecordingSubmoduleGateway(
    private val entries: List<Submodule> = emptyList(),
    private val failure: UndineException? = null,
) : SubmoduleGateway {
    val initialized = mutableListOf<String>()
    override suspend fun list(): List<Submodule> = failure?.let { throw it } ?: entries
    override suspend fun initialize(path: String, recursive: Boolean) { initialized += path }
    override suspend fun update(path: String, recursive: Boolean) = Unit
    override suspend fun add(url: String, path: String, branch: String?): Submodule = error("사용하지 않는다")
    override suspend fun remove(path: String, confirmed: Boolean) = error("사용하지 않는다")
}

private class RecordingWorktreeGateway(
    private val listing: WorktreeListing,
    private val removeFailure: UndineException? = null,
) : WorktreeGateway {
    val removeRequests = mutableListOf<String>()
    override suspend fun list(): WorktreeListing = listing
    override suspend fun add(path: RepositoryPath, branch: RefName): Worktree = error("사용하지 않는다")
    override suspend fun remove(name: String) {
        removeRequests += name
        removeFailure?.let { throw it }
    }
}
