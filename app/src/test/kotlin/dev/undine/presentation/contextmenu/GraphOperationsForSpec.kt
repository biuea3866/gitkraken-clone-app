package dev.undine.presentation.contextmenu

import dev.undine.domain.BranchTarget
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphDropRefusal
import dev.undine.domain.graphops.GraphOperation
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val MAIN = RefName("main")
private val FEATURE = RefName("feature/login")
private val REMOTE_MAIN = RefName("origin/main")
private val TAG = RefName("v1.0.0")

private val BRANCH_HEAD = commitId(1)
private val PICKED = commitId(2)

private val LOCAL_BRANCH = GraphContextTarget.Branch(FEATURE, BRANCH_HEAD, isRemote = false)
private val REMOTE_BRANCH = GraphContextTarget.Branch(REMOTE_MAIN, BRANCH_HEAD, isRemote = true)
private val LIGHTWEIGHT_TAG = GraphContextTarget.Tag(TAG, BRANCH_HEAD, isAnnotated = false)

/** 선택한 커밋이 있고 현재 브랜치는 `main` 인 평범한 상태. */
private val SELECTION = GraphContextSelection(commit = PICKED, currentBranch = MAIN)

/** detached HEAD — 커밋은 골랐지만 체크아웃된 브랜치가 없다. */
private val DETACHED = GraphContextSelection(commit = PICKED, currentBranch = null)

/**
 * 우클릭 메뉴와 팔레트가 **공유하는 단일 산출 함수**의 계약 (결정 D10).
 *
 * 산출은 조작 종류가 아니라 **값 전체**로 못박는다 — 종류만 세면 수행 브랜치가 이름 스냅샷으로
 * 바뀌어도 통과한다.
 */
class GraphOperationsForSpec : FunSpec({

    test("커밋 대상은 현재 브랜치로의 cherry-pick 하나만 산출한다") {
        val operations = graphOperationsFor(GraphContextTarget.Commit(PICKED), SELECTION)

        operations shouldContainExactly listOf(
            GraphOperation.CherryPick(PICKED, BranchTarget.Current),
        )
    }

    test("로컬 브랜치 대상은 merge·rebase·선택 커밋으로의 reset 셋을 산출한다") {
        val operations = graphOperationsFor(LOCAL_BRANCH, SELECTION)

        operations shouldContainExactly listOf(
            GraphOperation.Merge(FEATURE, BranchTarget.Current),
            GraphOperation.Rebase(BranchTarget.Current, FEATURE),
            GraphOperation.ResetBranch(FEATURE, PICKED),
        )
    }

    test("원격 브랜치 대상에는 로컬 전용 조작인 reset 이 없다") {
        val operations = graphOperationsFor(REMOTE_BRANCH, SELECTION)

        operations shouldContainExactly listOf(
            GraphOperation.Merge(REMOTE_MAIN, BranchTarget.Current),
            GraphOperation.Rebase(BranchTarget.Current, REMOTE_MAIN),
        )
        operations.filterIsInstance<GraphOperation.ResetBranch>().shouldBeEmpty()
    }

    test("태그 대상은 태그 이동 하나만 산출한다") {
        val operations = graphOperationsFor(LIGHTWEIGHT_TAG, SELECTION)

        operations shouldContainExactly listOf(GraphOperation.MoveTag(TAG, PICKED))
    }

    test("수행 브랜치는 이름 스냅샷이 아니라 실행 시점에 판정되는 현재 브랜치다") {
        val operations = graphOperationsFor(LOCAL_BRANCH, SELECTION)

        // 지금 체크아웃이 main 이라고 해서 main 을 이름으로 박으면, 실행 전에 체크아웃이 바뀔 때
        // 지목하지 않은 브랜치에서 실행된다.
        operations.filterIsInstance<GraphOperation.Merge>().single().into shouldBe BranchTarget.Current
        operations.filterIsInstance<GraphOperation.Rebase>().single().branch shouldBe BranchTarget.Current
    }

    test("고른 커밋이 없으면 reset·태그 이동은 사라지지 않고 옮길 것이 없다는 사유로 막힌다") {
        val nothingPicked = GraphContextSelection(commit = null, currentBranch = MAIN)

        val branchEntries = graphMenuEntriesFor(LOCAL_BRANCH, nothingPicked)
        val reset = branchEntries.single { it.operation is GraphOperation.ResetBranch }
        reset.enabled shouldBe false
        reset.blockedReason shouldBe GraphDropRefusal.SAME_COMMIT

        val tagEntry = graphMenuEntriesFor(LIGHTWEIGHT_TAG, nothingPicked).single()
        tagEntry.enabled shouldBe false
        tagEntry.blockedReason shouldBe GraphDropRefusal.SAME_COMMIT
    }

    test("이미 그 커밋을 가리키는 브랜치의 reset 은 바뀔 것이 없어 막힌다") {
        val pickedIsHead = GraphContextSelection(commit = BRANCH_HEAD, currentBranch = MAIN)

        val reset = graphMenuEntriesFor(LOCAL_BRANCH, pickedIsHead)
            .single { it.operation is GraphOperation.ResetBranch }

        reset.blockedReason shouldBe GraphDropRefusal.SAME_COMMIT
    }

    test("현재 브랜치를 지목하면 merge·rebase 는 자기 자신 대상이라 막힌다") {
        val currentBranch = GraphContextTarget.Branch(MAIN, BRANCH_HEAD, isRemote = false)

        val entries = graphMenuEntriesFor(currentBranch, SELECTION)

        // 항목은 남는다 — 사라지면 사용자는 그 조작이 없다고 읽는다 (결정 D3).
        entries.map { it.operation.kind() } shouldContainExactly listOf(
            GraphOperationKind.MERGE,
            GraphOperationKind.REBASE,
            GraphOperationKind.RESET_BRANCH,
        )
        entries.first { it.operation is GraphOperation.Merge }.blockedReason shouldBe GraphDropRefusal.SAME_REF
        entries.first { it.operation is GraphOperation.Rebase }.blockedReason shouldBe GraphDropRefusal.SAME_REF
    }

    test("annotated 태그의 이동은 목록에 남되 이동할 수 없다는 사유로 막힌다") {
        val annotated = GraphContextTarget.Tag(TAG, BRANCH_HEAD, isAnnotated = true)

        val entry = graphMenuEntriesFor(annotated, SELECTION).single()

        entry.operation shouldBe GraphOperation.MoveTag(TAG, PICKED)
        entry.blockedReason shouldBe GraphDropRefusal.ANNOTATED_TAG
    }

    test("지목한 대상이 없으면 고른 커밋이 대상이 된다") {
        val entries = graphMenuEntriesForPicked(target = null, selection = SELECTION)

        entries.map { it.operation } shouldContainExactly
            listOf(GraphOperation.CherryPick(PICKED, BranchTarget.Current))
    }

    test("지목한 대상도 고른 커밋도 없으면 아무 조작도 산출되지 않는다") {
        graphMenuEntriesForPicked(
            target = null,
            selection = GraphContextSelection(commit = null, currentBranch = MAIN),
        ).shouldBeEmpty()
    }

    test("지목한 대상이 있으면 고른 커밋보다 그 대상이 우선한다") {
        val entries = graphMenuEntriesForPicked(target = LOCAL_BRANCH, selection = SELECTION)

        entries.map { it.operation.kind() } shouldContainExactly listOf(
            GraphOperationKind.MERGE,
            GraphOperationKind.REBASE,
            GraphOperationKind.RESET_BRANCH,
        )
    }

    test("detached HEAD 면 현재 브랜치로의 cherry-pick 은 목록에 남되 브랜치가 없다는 사유로 막힌다") {
        val entry = graphMenuEntriesFor(GraphContextTarget.Commit(PICKED), DETACHED).single()

        // 항목은 남는다 — 사라지면 사용자는 cherry-pick 이 없는 기능이라고 읽는다 (결정 D3).
        entry.operation shouldBe GraphOperation.CherryPick(PICKED, BranchTarget.Current)
        entry.enabled shouldBe false
        entry.blockedReason shouldBe GraphDropRefusal.NO_CURRENT_BRANCH
    }

    test("detached HEAD 면 브랜치 merge·rebase 는 막히고 reset 은 막히지 않는다") {
        val entries = graphMenuEntriesFor(LOCAL_BRANCH, DETACHED)

        // 셋 다 목록에 남는다.
        entries.map { it.operation.kind() } shouldContainExactly listOf(
            GraphOperationKind.MERGE,
            GraphOperationKind.REBASE,
            GraphOperationKind.RESET_BRANCH,
        )
        // 현재 브랜치 위에서 도는 둘만 막힌다.
        entries.single { it.operation is GraphOperation.Merge }
            .blockedReason shouldBe GraphDropRefusal.NO_CURRENT_BRANCH
        entries.single { it.operation is GraphOperation.Rebase }
            .blockedReason shouldBe GraphDropRefusal.NO_CURRENT_BRANCH
        // reset 은 이름으로 지목한 브랜치를 옮기므로 체크아웃과 무관하다 — 막으면 할 수 있는 일을 막는다.
        entries.single { it.operation is GraphOperation.ResetBranch }.enabled shouldBe true
    }

    test("detached HEAD 여도 태그 이동은 이름으로 지목한 조작이라 실행할 수 있다") {
        val entry = graphMenuEntriesFor(LIGHTWEIGHT_TAG, DETACHED).single()

        entry.operation shouldBe GraphOperation.MoveTag(TAG, PICKED)
        entry.enabled shouldBe true
    }

    test("특정 종류만 묻는 진입점도 같은 판정을 받는다 — detached 면 병합 항목이 사유와 함께 막힌다") {
        val blocked = graphMenuEntryOf(GraphOperationKind.MERGE, LOCAL_BRANCH, DETACHED)
        val available = graphMenuEntryOf(GraphOperationKind.MERGE, LOCAL_BRANCH, SELECTION)

        blocked?.blockedReason shouldBe GraphDropRefusal.NO_CURRENT_BRANCH
        available?.enabled shouldBe true
        // 대상에 성립하지 않는 종류는 `null` 이다 — 원격 브랜치에는 reset 을 내지 않는다 (결정 D10).
        graphMenuEntryOf(GraphOperationKind.RESET_BRANCH, REMOTE_BRANCH, SELECTION) shouldBe null
    }

    test("실행할 수 있는 항목에는 사유가 붙지 않는다") {
        val entries = graphMenuEntriesFor(LOCAL_BRANCH, SELECTION)

        entries.filter { it.enabled }.map { it.blockedReason } shouldContainExactly listOf(null, null, null)
    }
})
