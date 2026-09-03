package dev.undine.presentation.sidebar

import dev.undine.application.sidebar.SidebarRefs
import dev.undine.application.submodule.InitializeSubmoduleUseCase
import dev.undine.application.submodule.LoadSubmodulesUseCase
import dev.undine.application.submodule.UpdateSubmoduleUseCase
import dev.undine.application.undo.OperationRecorder
import dev.undine.application.worktree.AddWorktreeUseCase
import dev.undine.application.worktree.LoadWorktreesUseCase
import dev.undine.application.worktree.RemoveWorktreeUseCase
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleGateway
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeGateway
import dev.undine.domain.worktree.WorktreeListing
import dev.undine.domain.worktree.WorktreeState
import dev.undine.presentation.submodule.SubmodulePanelActions
import dev.undine.presentation.submodule.SubmodulePanelState
import dev.undine.presentation.submodule.WorktreePanelActions
import dev.undine.presentation.submodule.WorktreePanelState
import dev.undine.testsupport.PassThroughChangeRecordingOrder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val SAMPLE_SUBMODULE = Submodule("modules/core", null, SubmoduleState(true, false, false))
private val SAMPLE_WORKTREE =
    Worktree("feature", RepositoryPath("/repo-feature"), RefName("feature"), WorktreeState.LINKED)

class SubmoduleWorktreeSidebarNodeSpec : FunSpec({
    test("사이드바는 서브모듈과 worktree를 별도 하위 섹션·안정 키로 표현한다") {
        val nodes = buildSidebarNodes(
            refs = SidebarRefs(emptyList(), emptyList(), emptyList()),
            expandedGroups = SidebarGroup.entries.toSet(),
            filter = "",
            submodules = listOf(SAMPLE_SUBMODULE),
            worktrees = listOf(SAMPLE_WORKTREE),
        )

        nodes.filterIsInstance<SidebarNode.GroupHeader>().map { it.group } shouldContainExactly
            SidebarGroup.entries.toList()
        nodes.filterIsInstance<SidebarNode.SubmoduleRow>().single().key shouldBe "submodule:modules/core"
        nodes.filterIsInstance<SidebarNode.WorktreeRow>().single().key shouldBe "worktree:feature"
    }

    test("사이드바 하위 섹션은 패널이 읽은 실제 목록을 그대로 받아 행으로 낸다") {
        val submodulePanel = submodulePanel()
        val worktreePanel = worktreePanel()
        submodulePanel.refresh()
        worktreePanel.refresh()

        val state = SidebarStateHarness(
            submodules = submodulePanel::submodules,
            worktrees = worktreePanel::worktrees,
        ).loaded()

        state.nodes.filterIsInstance<SidebarNode.SubmoduleRow>()
            .map { it.submodule.path } shouldContainExactly listOf("modules/core")
        state.nodes.filterIsInstance<SidebarNode.WorktreeRow>()
            .map { it.worktree.name } shouldContainExactly listOf("feature")
        state.nodes.filterIsInstance<SidebarNode.GroupHeader>()
            .single { it.group == SidebarGroup.WORKTREES }.itemCount shouldBe 1
    }
})

private fun recorder(): OperationRecorder {
    val refGateway = mockk<RefGateway>()
    coEvery { refGateway.listBranches() } returns listOf(
        Branch(
            RefName("main"),
            CommitId.of("d".repeat(40)),
            isCurrent = true,
            isRemote = false,
            upstream = null,
            ahead = 0,
            behind = 0,
        ),
    )
    return OperationRecorder(refGateway, UndoStack(), changeRecordingOrder = PassThroughChangeRecordingOrder)
}

private fun submodulePanel(): SubmodulePanelState {
    val gateway = mockk<SubmoduleGateway>(relaxUnitFun = true)
    coEvery { gateway.list() } returns listOf(SAMPLE_SUBMODULE)
    return SubmodulePanelState(
        actions = SubmodulePanelActions(
            load = LoadSubmodulesUseCase(gateway),
            initialize = InitializeSubmoduleUseCase(gateway, recorder()),
            update = UpdateSubmoduleUseCase(gateway, recorder()),
        ),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )
}

private fun worktreePanel(): WorktreePanelState {
    val gateway = mockk<WorktreeGateway>(relaxUnitFun = true)
    coEvery { gateway.list() } returns WorktreeListing(listOf(SAMPLE_WORKTREE), emptyList())
    return WorktreePanelState(
        actions = WorktreePanelActions(
            load = LoadWorktreesUseCase(gateway),
            add = AddWorktreeUseCase(gateway, recorder()),
            remove = RemoveWorktreeUseCase(gateway, recorder()),
        ),
        currentWorktree = RepositoryPath("/repo"),
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )
}
