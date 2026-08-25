package dev.undine.presentation.sidebar

import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.submodule.Submodule
import dev.undine.domain.submodule.SubmoduleState
import dev.undine.domain.worktree.Worktree
import dev.undine.domain.worktree.WorktreeState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SubmoduleWorktreeSidebarNodeSpec : FunSpec({
    test("사이드바는 서브모듈과 worktree를 별도 하위 섹션·안정 키로 표현한다") {
        val nodes = buildSidebarNodes(
            refs = SidebarRefs(emptyList(), emptyList(), emptyList()),
            expandedGroups = SidebarGroup.entries.toSet(),
            filter = "",
            submodules = listOf(
                Submodule("modules/core", null, SubmoduleState(true, false, false)),
            ),
            worktrees = listOf(
                Worktree("feature", RepositoryPath("/repo-feature"), RefName("feature"), WorktreeState.LINKED),
            ),
        )

        nodes.filterIsInstance<SidebarNode.GroupHeader>().map { it.group } shouldContainExactly
            SidebarGroup.entries.toList()
        nodes.filterIsInstance<SidebarNode.SubmoduleRow>().single().key shouldBe "submodule:modules/core"
        nodes.filterIsInstance<SidebarNode.WorktreeRow>().single().key shouldBe "worktree:feature"
    }
})
