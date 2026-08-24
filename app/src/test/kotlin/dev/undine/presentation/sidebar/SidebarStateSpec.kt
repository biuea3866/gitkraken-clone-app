package dev.undine.presentation.sidebar

import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.RefName
import dev.undine.domain.UndineException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred

private fun List<SidebarNode>.branchNames(): List<String> =
    filterIsInstance<SidebarNode.BranchRow>().map { it.branch.name.value }

private fun List<SidebarNode>.headerFor(group: SidebarGroup): SidebarNode.GroupHeader =
    filterIsInstance<SidebarNode.GroupHeader>().first { it.group == group }

/** 사이드바 상태 홀더 — 그룹 구성·필터·접힘·삭제 확인 절차·실패 전달. */
class SidebarStateSpec : FunSpec({

    test("불러온 참조가 로컬·원격 브랜치, 태그, 스태시 네 그룹으로 묶인다") {
        val state = SidebarStateHarness().loaded()

        state.status.shouldBeInstanceOf<SidebarStatus.Ready>()
        val nodes = state.nodes
        nodes.headerFor(SidebarGroup.LOCAL_BRANCHES).itemCount shouldBe 2
        nodes.headerFor(SidebarGroup.REMOTE_BRANCHES).itemCount shouldBe 1
        nodes.headerFor(SidebarGroup.TAGS).itemCount shouldBe 1
        nodes.headerFor(SidebarGroup.STASHES).itemCount shouldBe 1
        nodes.branchNames() shouldContainExactly listOf("main", "feature/login", "origin/main")
        nodes.filterIsInstance<SidebarNode.TagRow>() shouldHaveSize 1
        nodes.filterIsInstance<SidebarNode.StashRow>() shouldHaveSize 1
    }

    test("그룹을 접으면 그 그룹의 항목만 사라지고 헤더는 남는다") {
        val state = SidebarStateHarness().loaded()

        state.toggleGroup(SidebarGroup.LOCAL_BRANCHES)

        state.nodes.headerFor(SidebarGroup.LOCAL_BRANCHES).expanded shouldBe false
        state.nodes.branchNames() shouldContainExactly listOf("origin/main")
        state.nodes.filterIsInstance<SidebarNode.TagRow>() shouldHaveSize 1

        state.toggleGroup(SidebarGroup.LOCAL_BRANCHES)
        state.nodes.branchNames() shouldContainExactly listOf("main", "feature/login", "origin/main")
    }

    test("접힌 그룹도 헤더의 항목 수는 그대로 보여준다") {
        val state = SidebarStateHarness().loaded()

        state.toggleGroup(SidebarGroup.LOCAL_BRANCHES)

        state.nodes.headerFor(SidebarGroup.LOCAL_BRANCHES).itemCount shouldBe 2
    }

    test("필터는 브랜치 목록만 좁히고 대소문자를 가리지 않는다") {
        val state = SidebarStateHarness().loaded()

        state.updateFilter("LOG")

        state.nodes.branchNames() shouldContainExactly listOf("feature/login")
        state.nodes.filterIsInstance<SidebarNode.TagRow>() shouldHaveSize 1
        state.nodes.visibleBranchCount shouldBe 1
    }

    test("필터에 걸리는 브랜치가 없으면 보이는 브랜치 수가 0 이 된다") {
        val state = SidebarStateHarness().loaded()

        state.updateFilter("존재하지-않는-브랜치")

        state.nodes.visibleBranchCount shouldBe 0
        state.nodes.branchNames().shouldHaveSize(0)
    }

    test("브랜치가 0건이면 보이는 브랜치 수가 0 이고 태그·스태시만 남는다") {
        val state = SidebarStateHarness().loaded(
            SidebarRefs(branches = emptyList(), tags = listOf(tagOf("v1.0.0")), stashes = listOf(stashOf(0, "wip"))),
        )

        state.nodes.visibleBranchCount shouldBe 0
        state.nodes.filterIsInstance<SidebarNode.TagRow>() shouldHaveSize 1
        state.nodes.filterIsInstance<SidebarNode.StashRow>() shouldHaveSize 1
    }

    test("노드 키는 항목마다 고유하고 필터가 바뀌어도 같은 브랜치는 같은 키를 유지한다") {
        val state = SidebarStateHarness().loaded()
        val before = state.nodes.filterIsInstance<SidebarNode.BranchRow>().associate { it.branch.name.value to it.key }

        state.nodes.map { it.key }.toSet() shouldHaveSize state.nodes.size

        state.updateFilter("main")
        val after = state.nodes.filterIsInstance<SidebarNode.BranchRow>().associate { it.branch.name.value to it.key }

        after.keys.forEach { name -> after[name] shouldBe before[name] }
    }

    test("같은 이름의 로컬·원격 브랜치와 동명 태그가 서로 다른 키를 갖는다") {
        val state = SidebarStateHarness().loaded(
            SidebarRefs(
                branches = listOf(branchOf("main"), branchOf("main", isRemote = true)),
                tags = listOf(tagOf("main")),
                stashes = emptyList(),
            ),
        )

        state.nodes.map { it.key }.toSet() shouldHaveSize state.nodes.size
    }

    test("스태시 노드는 StashEntry.target 을 식별자로 쓴다") {
        val state = SidebarStateHarness().loaded()

        val stash = state.nodes.filterIsInstance<SidebarNode.StashRow>().first()
        stash.key shouldBe "stash:${stash.entry.target.value}"
    }

    test("목록 조회가 실패하면 빈 목록이 아니라 실패 상태로 전달된다") {
        val harness = SidebarStateHarness()
        coEvery { harness.refGateway.listBranches() } throws
            UndineException.GitOperationFailed("ref.listBranches")

        harness.state.refresh()

        val status = harness.state.status
        status.shouldBeInstanceOf<SidebarStatus.Failed>()
        status.cause.shouldBeInstanceOf<UndineException.GitOperationFailed>()
        harness.state.nodes.shouldHaveSize(0)
    }

    test("체크아웃은 UseCase 를 거쳐 강제 없이 요청되고 목록을 다시 불러온다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()

        state.checkout(SAMPLE_FEATURE)

        coVerify(exactly = 1) { harness.refGateway.checkout(RefName("feature/login"), force = false) }
        coVerify(atLeast = 2) { harness.refGateway.listBranches() }
    }

    test("체크아웃이 실패하면 성공으로 바꾸지 않고 실패 사유를 화면 상태에 남긴다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.checkout(any(), any()) } throws
            UndineException.DirtyWorkingTree(listOf("src/Main.kt"))

        state.checkout(SAMPLE_FEATURE)

        state.actionFailure.shouldBeInstanceOf<UndineException.DirtyWorkingTree>()
    }

    test("이름 변경은 이전 이름과 새 이름을 그대로 위임한다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()

        state.startRename(SAMPLE_FEATURE)
        state.renameTarget.shouldNotBeNull()
        state.submitRename("feature/signin")

        coVerify(exactly = 1) {
            harness.refGateway.renameBranch(RefName("feature/login"), RefName("feature/signin"))
        }
        state.renameTarget.shouldBeNull()
    }

    test("이름 변경 대화상자를 닫으면 아무 것도 요청하지 않는다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()

        state.startRename(SAMPLE_FEATURE)
        state.dismiss()

        state.renameTarget.shouldBeNull()
        coVerify(exactly = 0) { harness.refGateway.renameBranch(any(), any()) }
    }

    test("삭제 요청만으로는 어떤 삭제도 실행되지 않는다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()

        state.requestDelete(SAMPLE_FEATURE)

        state.confirmation.shouldBeInstanceOf<SidebarConfirmation.DeleteBranch>()
        coVerify(exactly = 0) { harness.refGateway.deleteBranch(any(), any()) }
    }

    test("삭제 확인 전에 취소하면 삭제를 호출하지 않는다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()

        state.requestDelete(SAMPLE_FEATURE)
        state.dismiss()

        state.confirmation.shouldBeNull()
        coVerify(exactly = 0) { harness.refGateway.deleteBranch(any(), any()) }
    }

    test("병합된 브랜치는 확인 한 번으로 비강제 삭제만 하고 끝난다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) } returns
            DeleteBranchResult.DELETED

        state.requestDelete(SAMPLE_FEATURE)
        state.confirmDelete()

        coVerify(exactly = 1) { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) }
        coVerify(exactly = 0) { harness.refGateway.deleteBranch(RefName("feature/login"), force = true) }
        state.confirmation.shouldBeNull()
    }

    test("미병합 브랜치는 비강제 삭제 거부 뒤 도달 불가 경고 확인 단계로 넘어간다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = false) } returns
            DeleteBranchResult.REFUSED_UNMERGED

        state.requestDelete(branchOf("feature/wip"))
        state.confirmDelete()

        state.confirmation.shouldBeInstanceOf<SidebarConfirmation.ForceDeleteUnmerged>()
        coVerify(exactly = 0) { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) }
    }

    test("도달 불가 경고를 확인해야 비로소 강제 삭제를 요청한다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = false) } returns
            DeleteBranchResult.REFUSED_UNMERGED
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) } returns
            DeleteBranchResult.DELETED

        state.requestDelete(branchOf("feature/wip"))
        state.confirmDelete()
        state.confirmDelete()

        coVerify(exactly = 1) { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) }
        state.confirmation.shouldBeNull()
    }

    test("도달 불가 경고에서 취소하면 강제 삭제를 요청하지 않는다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = false) } returns
            DeleteBranchResult.REFUSED_UNMERGED

        state.requestDelete(branchOf("feature/wip"))
        state.confirmDelete()
        state.dismiss()

        state.confirmation.shouldBeNull()
        coVerify(exactly = 0) { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) }
    }

    test("삭제 응답을 기다리는 동안 확인을 연달아 눌러도 삭제는 한 번만 나간다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        val pendingDelete = CompletableDeferred<DeleteBranchResult>()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) } coAnswers
            { pendingDelete.await() }

        state.requestDelete(SAMPLE_FEATURE)
        state.confirmDelete()
        state.deleteInProgress shouldBe true
        state.confirmDelete()
        state.confirmDelete()

        coVerify(exactly = 1) { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) }

        pendingDelete.complete(DeleteBranchResult.DELETED)
        state.deleteInProgress shouldBe false
        state.confirmation.shouldBeNull()
    }

    test("미병합 경고 단계도 응답 대기 중 연속 확인으로 강제 삭제를 두 번 요청하지 않는다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        val pendingForceDelete = CompletableDeferred<DeleteBranchResult>()
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = false) } returns
            DeleteBranchResult.REFUSED_UNMERGED
        coEvery { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) } coAnswers
            { pendingForceDelete.await() }

        state.requestDelete(branchOf("feature/wip"))
        state.confirmDelete()
        state.confirmation.shouldBeInstanceOf<SidebarConfirmation.ForceDeleteUnmerged>()
        state.confirmDelete()
        state.confirmDelete()

        coVerify(exactly = 1) { harness.refGateway.deleteBranch(RefName("feature/wip"), force = true) }

        pendingForceDelete.complete(DeleteBranchResult.DELETED)
        state.deleteInProgress shouldBe false
    }

    test("삭제가 실패하면 진행 상태를 풀어 다시 시도할 수 있게 한다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(any(), any()) } throws
            UndineException.StateViolation("현재 체크아웃된 브랜치는 삭제할 수 없습니다")

        state.requestDelete(branchOf("main"))
        state.confirmDelete()

        state.deleteInProgress shouldBe false
    }

    test("삭제가 실패하면 실패 사유를 화면 상태에 남긴다") {
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        coEvery { harness.refGateway.deleteBranch(any(), any()) } throws
            UndineException.StateViolation("현재 체크아웃된 브랜치는 삭제할 수 없습니다")

        state.requestDelete(branchOf("main"))
        state.confirmDelete()

        state.actionFailure.shouldBeInstanceOf<UndineException.StateViolation>()
    }

    test("메뉴는 한 번에 하나만 열리고 다시 누르면 닫힌다") {
        val state = SidebarStateHarness().loaded()

        val main = SAMPLE_MAIN
        val feature = SAMPLE_FEATURE

        state.toggleMenu(main)
        state.isMenuOpen(main) shouldBe true

        state.toggleMenu(feature)
        state.isMenuOpen(main) shouldBe false
        state.isMenuOpen(feature) shouldBe true

        state.toggleMenu(feature)
        state.openMenu.shouldBeNull()
    }

    test("ahead·behind 가 둘 다 0 이면 배지를 감추고 한쪽만 0 이면 그쪽만 감춘다") {
        SidebarBadge.of(branchOf("a", ahead = 0, behind = 0)).shouldBeNull()
        SidebarBadge.of(branchOf("b", ahead = 2, behind = 1)) shouldBe SidebarBadge(ahead = 2, behind = 1)
        SidebarBadge.of(branchOf("c", ahead = 3, behind = 0)) shouldBe SidebarBadge(ahead = 3, behind = 0)
        SidebarBadge.of(branchOf("d", ahead = 0, behind = 4)) shouldBe SidebarBadge(ahead = 0, behind = 4)
    }

    test("원격 추적 브랜치는 이름 변경·삭제를 시작하지 않는다") {
        // Gateway 는 짧은 이름을 refs/heads/ 로 해석하므로, 원격 행의 삭제는 동명 로컬 브랜치를 지운다.
        val harness = SidebarStateHarness()
        val state = harness.loaded()
        val remote = SAMPLE_REMOTE_MAIN

        state.requestDelete(remote)
        state.confirmation.shouldBeNull()

        state.startRename(remote)
        state.renameTarget.shouldBeNull()

        coVerify(exactly = 0) { harness.refGateway.deleteBranch(any(), any()) }
        coVerify(exactly = 0) { harness.refGateway.renameBranch(any(), any()) }
    }

    test("이름이 겹치는 로컬·원격 행의 메뉴는 서로 독립이다") {
        val local = branchOf("origin/main")
        val remote = SAMPLE_REMOTE_MAIN
        val state = SidebarStateHarness().loaded(
            sampleRefs().copy(branches = listOf(local, remote)),
        )

        state.toggleMenu(remote)

        // 이름만으로 메뉴 상태를 잡으면 로컬 행 메뉴까지 함께 열린다.
        state.isMenuOpen(remote) shouldBe true
        state.isMenuOpen(local) shouldBe false
    }

    test("삭제 결과가 늦게 오면 그 사이 닫힌 대화상자를 다시 열지 않는다") {
        val harness = SidebarStateHarness()
        val gate = CompletableDeferred<Unit>()
        val unmerged = SAMPLE_FEATURE
        coEvery { harness.refGateway.deleteBranch(unmerged.name, false) } coAnswers {
            gate.await()
            DeleteBranchResult.REFUSED_UNMERGED
        }
        val state = harness.loaded()

        state.requestDelete(unmerged)
        state.confirmDelete()
        // 응답을 기다리는 동안 사용자가 취소한다.
        state.dismiss()
        gate.complete(Unit)

        // 취소한 대화상자가 강제 삭제 확인으로 되살아나면 사용자가 보지 않은 파괴적 동작이 한 번에 열린다.
        state.confirmation.shouldBeNull()
    }

    test("삭제 결과가 늦게 오면 그 사이 요청한 다른 확인을 지우지 않는다") {
        val harness = SidebarStateHarness()
        val gate = CompletableDeferred<Unit>()
        val first = SAMPLE_FEATURE
        val second = SAMPLE_MAIN
        coEvery { harness.refGateway.deleteBranch(first.name, false) } coAnswers {
            gate.await()
            DeleteBranchResult.DELETED
        }
        val state = harness.loaded()

        state.requestDelete(first)
        state.confirmDelete()
        state.requestDelete(second)
        gate.complete(Unit)

        state.confirmation shouldBe SidebarConfirmation.DeleteBranch(second)
    }

    test("먼저 시작한 조회가 늦게 끝나도 최신 목록을 덮어쓰지 않는다") {
        val harness = SidebarStateHarness()
        val slowGate = CompletableDeferred<Unit>()
        val stale = sampleRefs()
        val fresh = sampleRefs().copy(branches = listOf(SAMPLE_MAIN))

        // 첫 조회는 붙잡아 두고, 두 번째 조회가 먼저 끝나게 한다.
        coEvery { harness.refGateway.listBranches() } coAnswers {
            slowGate.await()
            stale.branches
        }
        coEvery { harness.refGateway.listTags() } returns stale.tags
        coEvery { harness.worktreeOpsGateway.stashList() } returns stale.stashes
        harness.state.refresh()

        coEvery { harness.refGateway.listBranches() } returns fresh.branches
        harness.state.refresh()

        slowGate.complete(Unit)

        // 늦게 끝난 과거 조회가 이겼다면 지운 브랜치가 목록에 되살아난다.
        val ready = harness.state.status.shouldBeInstanceOf<SidebarStatus.Ready>()
        ready.refs.branches shouldContainExactly fresh.branches
    }
})
