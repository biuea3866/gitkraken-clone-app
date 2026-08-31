package dev.undine.scenario

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.conflict.ConflictChoice
import dev.undine.domain.conflict.ConflictDocument
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.merge.AbortConfirmation
import dev.undine.domain.merge.MergeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import java.io.File

private const val SHARED = "base.txt"
private const val FEATURE = "feature"

/**
 * 시나리오 3·4 — 충돌 병합을 **해결해 이어가기**와 **중단해 되돌리기**.
 *
 * 둘을 한 파일에 두는 이유는 같은 충돌 상태에서 갈라지는 두 경로이고, 같은 셋업을 공유하기 때문이다.
 * 각 테스트는 자기 임시 저장소를 만든다 — 앞 테스트가 남긴 상태에 기대지 않는다.
 */
class ConflictRecoveryScenarioSpec : FunSpec({

    test("충돌을 해결하고 이어가면 병합 커밋이 생긴다") {
        val app = conflictedApp(tempdir())

        // 충돌이 감지되고 해결 대상이 목록으로 온다.
        app.loadConflicted.execute().map { it.path } shouldContainExactly listOf(SHARED)

        // 화면이 하는 그대로: 표식이 든 파일을 읽고 한쪽을 골라 저장한다.
        val document = ConflictDocument.parse(app.loadConflictContent.execute(SHARED))
            .choose(0, ConflictChoice.Take(ConflictSide.OURS))
        document.isResolved shouldBe true
        app.resolveConflict.execute(SHARED, document.render())

        app.loadConflicted.execute().shouldBeEmpty()
        val outcome = app.continueAfterResolve.execute(RepositoryState.MERGING)

        outcome.shouldBeInstanceOf<dev.undine.application.conflict.ContinueOutcome.Merged>()
            .result.shouldBeInstanceOf<MergeResult.Succeeded>()
        parentCountOfHead(app.work) shouldBe 2
        // 해결 결과가 워킹트리에 남고 표식은 사라진다.
        app.readFile(SHARED) shouldNotContain "<<<<<<<"
        app.readFile(SHARED) shouldBe "ours\n"
    }

    test("충돌을 중단하면 시작 전 이력과 워킹트리로 돌아온다") {
        val app = conflictedApp(tempdir())
        val startPoint = headOf(app.work)
        val before = app.messagesOldestFirst()

        // 중단은 사라질 경로를 보여 준 확인이 있어야 실행된다 (파괴적 연산 계약).
        val status = app.loadStatus.execute()
        val discarded = (status.staged.map { it.path } + status.unstaged.map { it.path } + status.conflicted)
            .distinct()
            .sorted()
        app.abortMergeOrRebase.execute(AbortConfirmation.ofDiscardedPaths(discarded))

        headOf(app.work) shouldBe startPoint
        app.messagesOldestFirst() shouldContainExactly before
        // 워킹트리도 시작 전으로 — 표식이 남으면 다음 작업이 그것을 커밋한다.
        app.readFile(SHARED) shouldBe "ours\n"
        app.loadConflicted.execute().shouldBeEmpty()
        app.loadStatus.execute().isClean shouldBe true
    }
})

/** `main` 과 `feature` 가 같은 파일을 다르게 고쳐 병합이 충돌한 상태의 앱. */
private suspend fun conflictedApp(work: File): ScenarioApp {
    seedRepository(work, file = SHARED, content = "base\n")
    val app = ScenarioApp(work)
    app.open()

    app.refs.createBranch(RefName("refs/heads/$FEATURE"), headCommitId(work))
    app.refs.checkout(RefName("refs/heads/$FEATURE"), force = false)
    app.writeFile(SHARED, "theirs\n")
    app.stageAndCommit("저쪽이 고친다", SHARED)

    app.refs.checkout(mainRef(), force = false)
    app.writeFile(SHARED, "ours\n")
    app.stageAndCommit("우리가 고친다", SHARED)

    val result = app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = false)
    result.result.shouldBeInstanceOf<MergeResult.Conflicted>().paths shouldContainExactly listOf(SHARED)
    return app
}

private fun parentCountOfHead(work: File): Int =
    Git.open(work).use { git -> git.log().setMaxCount(1).call().first().parentCount }
