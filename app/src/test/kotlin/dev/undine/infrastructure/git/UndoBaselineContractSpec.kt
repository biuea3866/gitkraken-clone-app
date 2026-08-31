package dev.undine.infrastructure.git

import dev.undine.domain.AmendConfirmation
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.merge.RebaseResult
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebasePlan
import dev.undine.infrastructure.git.cherrypick.CherryPickGatewayImpl
import dev.undine.infrastructure.git.merge.MergeGatewayImpl
import dev.undine.infrastructure.git.rebase.InteractiveRebaseGatewayImpl
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.INITIAL_BRANCH
import dev.undine.infrastructure.git.repository.commitFile
import dev.undine.infrastructure.git.repository.createDivergedBranches
import dev.undine.infrastructure.git.repository.initRepository
import dev.undine.infrastructure.git.staging.StagingGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.io.File

private const val SHARED_FILE = "shared.txt"
private const val FEATURE_BRANCH = "feature"

/** 열린 저장소 하나를 공유하는 Gateway 묶음. 배선과 같이 [GitAccess] 하나를 함께 쓴다. */
private class RepositoryUnderTest(work: File) {

    private val gitAccess = GitAccess()

    val staging = StagingGatewayImpl(gitAccess)
    val refs = RefGatewayImpl(gitAccess)
    val merge = MergeGatewayImpl(gitAccess)
    val cherryPick = CherryPickGatewayImpl(gitAccess)
    val interactiveRebase = InteractiveRebaseGatewayImpl(gitAccess)
    val worktreeOps = WorktreeOpsGatewayImpl(gitAccess)

    val work: File = work

    suspend fun open() {
        gitAccess.open(RepositoryPath(work.absolutePath)) { }
    }

    /** 지금 HEAD. 결과가 준 값과 대조할 정답이다. */
    fun head(): CommitId = Git.open(work).use { git ->
        CommitId.of(git.repository.resolve(Constants.HEAD).name)
    }

    fun currentBranch(): RefName? = Git.open(work).use { git ->
        git.repository.branch?.let { RefName(it) }
    }
}

private suspend fun repositoryWith(work: File, seed: Git.() -> Unit): RepositoryUnderTest {
    initRepository(work).use { git -> git.seed() }
    return RepositoryUnderTest(work).also { it.open() }
}

/** 지금 상태를 그대로 옮긴 기준 상태. 결과가 준 baseline 과 같아야 한다. */
private fun RepositoryUnderTest.expectedBaseline(): RepositoryBaseline =
    RepositoryBaseline(branch = currentBranch(), head = head())

/**
 * 되돌리기에 쓰는 값을 **변경과 같은 임계 구역에서** 결과로 돌려주는지 실제 저장소로 본다 (UND-73).
 *
 * 단위 스펙은 Gateway 대역이라 "UseCase 가 받은 값을 그대로 넘기는가" 까지만 본다. 그 값이 실제로
 * 변경 직전·직후를 가리키는지는 여기서만 증명된다 (testing 규칙 1).
 */
class UndoBaselineContractSpec : FunSpec({

    test("커밋 결과는 커밋 직전 HEAD 와 커밋 직후 기준 상태를 함께 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) { commitFile("a.txt", "a\n", "첫 커밋") }
        val before = repository.head()

        File(work, "b.txt").writeText("b\n")
        repository.staging.stage(listOf("b.txt"))
        val result = repository.staging.commit("둘째 커밋")

        result.previousHead shouldBe before
        result.commitId shouldNotBe before
        result.baseline shouldBe RepositoryBaseline(RefName(INITIAL_BRANCH), result.commitId)
    }

    test("커밋이 하나도 없던 저장소의 첫 커밋은 되돌릴 지점이 없다") {
        val work = tempdir()
        initRepository(work).use { }
        val repository = RepositoryUnderTest(work).also { it.open() }

        File(work, "a.txt").writeText("a\n")
        repository.staging.stage(listOf("a.txt"))
        val result = repository.staging.commit("첫 커밋")

        result.previousHead.shouldBeNull()
    }

    test("amend 결과는 고치기 전 원본 커밋을 되돌릴 지점으로 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) { commitFile("a.txt", "a\n", "고칠 커밋") }
        val original = repository.head()

        val result = repository.staging.amend("고친 메시지", AmendConfirmation.NotRequired)

        result.previousHead shouldBe original
        result.commitId shouldNotBe original
        // 기준 상태는 지금 브랜치와 고쳐 쓴 뒤의 HEAD 를 가리킨다 — 되돌리기의 expected 가 이 값이다.
        result.baseline shouldBe RepositoryBaseline(RefName(INITIAL_BRANCH), result.commitId)
        result.baseline shouldBe repository.expectedBaseline()
        // 백업 ref 가 원본을 살려 두므로 그 지점으로 되돌아갈 수 있다.
        Git.open(work).use { it.repository.resolve(original.value) } shouldNotBe null
    }

    test("체크아웃 결과는 옮기기 직전 브랜치와 옮긴 뒤 기준 상태를 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) { createDivergedBranches(SHARED_FILE) }

        val result = repository.refs.checkout(RefName(FEATURE_BRANCH), force = false)

        result.previousRef shouldBe RefName(INITIAL_BRANCH)
        result.baseline shouldBe repository.expectedBaseline()
        repository.currentBranch() shouldBe RefName(FEATURE_BRANCH)
    }

    test("병합 결과는 시작 전 지점과 병합 직후 기준 상태를 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("base.txt", "base\n", "base")
            branchCreate().setName(FEATURE_BRANCH).call()
            checkout().setName(FEATURE_BRANCH).call()
            commitFile("feature.txt", "feature\n", "feature")
            checkout().setName(INITIAL_BRANCH).call()
            commitFile("main.txt", "main\n", "main")
        }
        val before = repository.head()

        val result = repository.merge.merge(RefName(FEATURE_BRANCH), allowFastForward = false)

        val succeeded = result.shouldBeInstanceOf<MergeResult.Succeeded>()
        succeeded.previousHead shouldBe before
        succeeded.head shouldBe repository.head()
        succeeded.baseline shouldBe repository.expectedBaseline()
    }

    test("빨리 감기 병합도 시작 전 지점을 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("base.txt", "base\n", "base")
            branchCreate().setName(FEATURE_BRANCH).call()
            checkout().setName(FEATURE_BRANCH).call()
            commitFile("feature.txt", "feature\n", "feature")
            checkout().setName(INITIAL_BRANCH).call()
        }
        val before = repository.head()

        val succeeded = repository.merge.merge(RefName(FEATURE_BRANCH), allowFastForward = true)
            .shouldBeInstanceOf<MergeResult.Succeeded>()

        succeeded.fastForward shouldBe true
        succeeded.previousHead shouldBe before
        succeeded.baseline shouldBe repository.expectedBaseline()
    }

    test("리베이스 결과는 재배치 전 브랜치 끝을 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("base.txt", "base\n", "base")
            branchCreate().setName(FEATURE_BRANCH).call()
            commitFile("main.txt", "main\n", "main")
            checkout().setName(FEATURE_BRANCH).call()
            commitFile("feature.txt", "feature\n", "feature")
        }
        val before = repository.head()

        val succeeded = repository.merge.rebase(RefName(INITIAL_BRANCH))
            .shouldBeInstanceOf<RebaseResult.Succeeded>()

        succeeded.previousHead shouldBe before
        succeeded.baseline shouldBe repository.expectedBaseline()
    }

    test("충돌을 해결하고 이어간 병합도 시작 전 지점을 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) { createDivergedBranches(SHARED_FILE) }
        val before = repository.head()

        repository.merge.merge(RefName(FEATURE_BRANCH), allowFastForward = false)
            .shouldBeInstanceOf<MergeResult.Conflicted>()
        File(work, SHARED_FILE).writeText("resolved\n")
        Git.open(work).use { it.add().addFilepattern(SHARED_FILE).call() }

        val succeeded = repository.merge.continueMerge().shouldBeInstanceOf<MergeResult.Succeeded>()

        // 이어간 병합의 되돌리기는 **병합을 시작하기 전** 커밋으로 간다.
        succeeded.previousHead shouldBe before
        succeeded.baseline shouldBe repository.expectedBaseline()
    }

    test("충돌을 해결하고 이어간 리베이스는 시작 전 지점을 주고 그 값으로 되돌리면 복구된다") {
        val work = tempdir()
        val repository = repositoryWith(work) { createDivergedBranches(SHARED_FILE) }
        repository.refs.checkout(RefName(FEATURE_BRANCH), force = false)
        val before = repository.head()

        repository.merge.rebase(RefName(INITIAL_BRANCH)).shouldBeInstanceOf<RebaseResult.Conflicted>()
        File(work, SHARED_FILE).writeText("resolved\n")
        Git.open(work).use { it.add().addFilepattern(SHARED_FILE).call() }

        val succeeded = repository.merge.continueRebase().shouldBeInstanceOf<RebaseResult.Succeeded>()

        // 이어간 리베이스의 되돌리기는 **리베이스를 시작하기 전**(ORIG_HEAD) 커밋으로 간다.
        succeeded.previousHead shouldBe before
        succeeded.baseline shouldBe repository.expectedBaseline()

        // 결과가 준 값만으로 실제 복구가 되는지까지 본다 — 계약이 맞아도 복구가 안 되면 소용이 없다.
        repository.worktreeOps.hardResetBranch(
            RefName(FEATURE_BRANCH),
            to = succeeded.previousHead.shouldNotBeNull(),
            expected = succeeded.baseline.head.shouldNotBeNull(),
        )

        repository.head() shouldBe before
    }

    test("cherry-pick 단계는 적용 직전 HEAD 와 적용 직후 기준 상태를 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("base.txt", "base\n", "base")
            branchCreate().setName(FEATURE_BRANCH).call()
            checkout().setName(FEATURE_BRANCH).call()
            commitFile("picked.txt", "picked\n", "가져올 커밋")
            checkout().setName(INITIAL_BRANCH).call()
        }
        val source = Git.open(work).use { git ->
            CommitId.of(git.repository.resolve("refs/heads/$FEATURE_BRANCH").name)
        }
        val before = repository.head()

        val step = repository.cherryPick.apply(source, recordOrigin = false)
            .shouldBeInstanceOf<CherryPickStep.Created>()

        step.previousHead shouldBe before
        step.commit shouldBe repository.head()
        step.baseline shouldBe repository.expectedBaseline()
    }

    test("대화형 리베이스 완료 결과는 적용 직전 HEAD 를 준다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("base.txt", "base\n", "base")
            commitFile("a.txt", "a\n", "add a")
            commitFile("b.txt", "b\n", "add b")
        }
        val base = RefName("HEAD~2")
        val before = repository.head()
        val plan = RebasePlan.of(repository.interactiveRebase.listTargets(base)).move(from = 1, to = 0)

        val completed = repository.interactiveRebase.apply(base, plan)
            .shouldBeInstanceOf<InteractiveRebaseOutcome.Completed>()

        completed.previousHead shouldBe before
        completed.baseline shouldBe repository.expectedBaseline()
    }

    test("기대 위치가 어긋나면 조건부 되돌리기가 ref 를 덮어쓰지 않는다") {
        val work = tempdir()
        val repository = repositoryWith(work) {
            commitFile("a.txt", "a\n", "첫 커밋")
            commitFile("b.txt", "b\n", "둘째 커밋")
        }
        val actual = repository.head()
        val stale = CommitId.of("0".repeat(40))

        // 되돌리기는 "이 연산이 만든 위치" 일 때만 수행한다 (결정 G2·G5).
        shouldThrow<UndineException> {
            repository.worktreeOps.hardResetBranch(
                RefName(INITIAL_BRANCH),
                to = stale,
                expected = stale,
            )
        }

        repository.head() shouldBe actual
    }
})
