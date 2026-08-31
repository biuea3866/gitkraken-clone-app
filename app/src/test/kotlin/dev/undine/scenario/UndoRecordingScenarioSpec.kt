package dev.undine.scenario

import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.cherrypick.CherryPickAbortConfirmation
import dev.undine.domain.cherrypick.CherryPickResult
import dev.undine.domain.merge.MergeResult
import dev.undine.domain.rebase.RebasePlan
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.URIish
import java.io.File

private const val FEATURE = "feature"
private const val SHARED = "shared.txt"

private fun ScenarioApp.head(): CommitId = Git.open(work).use { git ->
    CommitId.of(git.repository.resolve(Constants.HEAD).name)
}

private fun ScenarioApp.currentBranch(): String? = Git.open(work).use { it.repository.branch }

/** 앱이 저장소를 여는 경로 그대로 연 시나리오 앱. */
private suspend fun openedApp(work: File): ScenarioApp {
    seedRepository(work)
    return ScenarioApp(work).also { it.open() }
}

/** 되돌린 뒤의 결과. 거부·실패를 성공으로 뭉개지 않도록 종류까지 확인한다. */
private suspend fun ScenarioApp.undoAndExpectUndone(): UndoOutcome.Undone {
    val execution = undoTop().shouldBeInstanceOf<UndoExecution.Completed>()
    return execution.outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
}

/**
 * 실제 임시 저장소에서 **기록 → 되돌리기 → 직전 상태 복구**까지 한 바퀴 돈다 (UND-79).
 *
 * 단위 스펙은 "어떤 전략으로 기록하는가" 까지만 본다. 그 전략이 **정말 복구하는가** 는 여기서만
 * 증명된다 — 되돌리기 대상이 늘었으니 각 전략이 실제로 복구하는지 확인한 뒤 머지한다 (티켓 롤백 항목).
 * 네트워크 원격은 쓰지 않는다 (testing 규칙 2).
 */
class UndoRecordingScenarioSpec : FunSpec({

    test("커밋하면 COMMIT 이 기록되고 되돌리기가 직전 커밋으로 복구한다") {
        val app = openedApp(tempdir())
        val before = app.head()

        app.writeFile("note.txt", "메모\n")
        app.stageFiles.execute(listOf("note.txt"))
        val outcome = app.commitStaged.execute("메모를 남긴다")

        outcome.undoRecordFailure shouldBe null
        app.undoStack.history().single().operation shouldBe GitOperationKind.COMMIT

        app.undoAndExpectUndone()

        app.head() shouldBe before
        // soft reset 이라 변경 내용은 인덱스에 남는다 — 커밋만 취소된다.
        app.readFile("note.txt") shouldBe "메모\n"
    }

    test("amend 하면 되돌리기가 고치기 전 커밋으로 복구한다") {
        val app = openedApp(tempdir())
        app.writeFile("note.txt", "메모\n")
        app.stageFiles.execute(listOf("note.txt"))
        app.commitStaged.execute("처음 메시지")
        val original = app.head()

        app.amendCommit.request("고친 메시지")
        app.head() shouldNotBe original

        app.undoAndExpectUndone()

        app.head() shouldBe original
        app.messagesOldestFirst() shouldContainExactly listOf("initial", "처음 메시지")
    }

    test("체크아웃하면 CHECKOUT 이 기록되고 되돌리기가 이전 브랜치로 돌아온다") {
        val app = openedApp(tempdir())
        Git.open(app.work).use { it.branchCreate().setName(FEATURE).call() }

        val outcome = app.checkoutBranch(RefName(FEATURE))

        outcome.undoRecordFailure shouldBe null
        app.currentBranch() shouldBe FEATURE
        app.undoStack.history().single().operation shouldBe GitOperationKind.CHECKOUT

        app.undoAndExpectUndone()

        app.currentBranch() shouldBe "main"
    }

    test("병합하면 MERGE 가 기록되고 되돌리기가 병합 전 이력으로 복구한다") {
        val app = openedApp(tempdir())
        divergeFeature(app)
        val beforeMerge = app.head()

        val outcome = app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = false)

        outcome.result.shouldBeInstanceOf<MergeResult.Succeeded>()
        // 셋업의 체크아웃도 이력에 남으므로 최신 한 건만 본다 — 이력은 최신 우선이다.
        app.undoStack.history().first().operation shouldBe GitOperationKind.MERGE

        app.undoAndExpectUndone()

        app.head() shouldBe beforeMerge
    }

    test("빨리 감기 병합도 되돌리기가 병합 전 위치로 복구한다") {
        val app = openedApp(tempdir())
        Git.open(app.work).use { git ->
            git.branchCreate().setName(FEATURE).call()
            git.checkout().setName(FEATURE).call()
        }
        app.writeFile("fast.txt", "빨리\n")
        app.stageAndCommit("빨리 감기 대상", "fast.txt")
        app.checkoutBranch(mainRef())
        val beforeMerge = app.head()

        val outcome = app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = true)
        outcome.result.shouldBeInstanceOf<MergeResult.Succeeded>().fastForward shouldBe true

        app.undoAndExpectUndone()

        app.head() shouldBe beforeMerge
    }

    test("리베이스하면 REBASE 가 기록되고 되돌리기가 재배치 전 커밋으로 복구한다") {
        val app = openedApp(tempdir())
        divergeFeature(app)
        app.checkoutBranch(RefName(FEATURE))
        val beforeRebase = app.head()

        app.rebaseBranch.execute(mainRef())
        app.head() shouldNotBe beforeRebase
        app.undoStack.history().first().operation shouldBe GitOperationKind.REBASE

        app.undoAndExpectUndone()

        app.head() shouldBe beforeRebase
    }

    test("cherry-pick 하면 CHERRY_PICK 이 기록되고 되돌리기가 적용 전으로 복구한다") {
        val app = openedApp(tempdir())
        Git.open(app.work).use { git ->
            git.branchCreate().setName(FEATURE).call()
            git.checkout().setName(FEATURE).call()
        }
        app.writeFile("picked.txt", "가져올 것\n")
        val picked = app.stageAndCommit("가져올 커밋", "picked.txt")
        app.checkoutBranch(mainRef())
        val beforePick = app.head()

        val outcome = app.cherryPickCommits.execute(listOf(picked))

        outcome.result.shouldBeInstanceOf<CherryPickResult.Applied>()
        app.undoStack.history().first().operation shouldBe GitOperationKind.CHERRY_PICK

        app.undoAndExpectUndone()

        app.head() shouldBe beforePick
        File(app.work, "picked.txt").exists() shouldBe false
    }

    test("충돌 전까지 적용된 cherry-pick 커밋도 기록되고 되돌리기가 적용 전으로 복구한다") {
        val app = openedApp(tempdir())
        val (clean, conflicting) = pickableCommits(app)
        val beforePick = app.head()

        val outcome = app.cherryPickCommits.execute(listOf(clean, conflicting))

        // 두 번째에서 멈췄지만 첫 커밋은 이미 적용됐다 — 중단해도 남으므로 기록돼야 한다.
        val conflicted = outcome.result.shouldBeInstanceOf<CherryPickResult.Conflicted>()
        conflicted.created.size shouldBe 1
        outcome.undoRecordFailure shouldBe null
        app.undoStack.history().first().operation shouldBe GitOperationKind.CHERRY_PICK

        // 중단은 마지막 단계의 시작점까지만 되감는다 — 첫 커밋은 그대로 남는다.
        app.abortCherryPick.execute(app.discardConfirmation())
        File(app.work, "clean.txt").exists() shouldBe true

        app.undoAndExpectUndone()

        app.head() shouldBe beforePick
        File(app.work, "clean.txt").exists() shouldBe false
    }

    test("부분 적용 뒤 충돌을 해결하고 이어간 cherry-pick 두 건을 역순 Undo하면 시작 전으로 복구한다") {
        val app = openedApp(tempdir())
        val (clean, conflicting) = pickableCommits(app)
        val beforePick = app.head()

        val stopped = app.cherryPickCommits.execute(listOf(clean, conflicting))
        stopped.result.shouldBeInstanceOf<CherryPickResult.Conflicted>().created.size shouldBe 1
        // 충돌 전까지 적용된 커밋은 중단해도 남으므로, 이어가기 전에 이미 한 건을 기록한다.
        app.undoStack.history().first().operation shouldBe GitOperationKind.CHERRY_PICK

        app.resolveConflict.execute(SHARED, "해결\n")
        val outcome = app.continueCherryPick.execute()

        outcome.result.shouldBeInstanceOf<CherryPickResult.Applied>()
        outcome.undoRecordFailure shouldBe null
        app.undoStack.history().take(2).map { it.operation } shouldContainExactly listOf(
            GitOperationKind.CHERRY_PICK,
            GitOperationKind.CHERRY_PICK,
        )

        app.undoAndExpectUndone()
        app.undoAndExpectUndone()

        app.head() shouldBe beforePick
        app.readFile(SHARED) shouldBe "우리\n"
        File(app.work, "clean.txt").exists() shouldBe false
    }

    test("충돌을 해결하고 이어간 병합은 되돌리기가 병합 시작 전으로 복구한다") {
        val app = openedApp(tempdir())
        conflictingMerge(app)
        val beforeMerge = Git.open(app.work).use { git ->
            CommitId.of(git.repository.readOrigHead().name)
        }

        app.writeFile(SHARED, "해결\n")
        app.resolveConflict.execute(SHARED, "해결\n")
        val outcome = app.continueAfterResolve.execute(RepositoryState.MERGING)

        outcome.undoRecordFailure shouldBe null
        app.undoStack.history().first().operation shouldBe GitOperationKind.MERGE

        app.undoAndExpectUndone()

        app.head() shouldBe beforeMerge
    }

    test("대화형 리베이스 계획을 적용하면 되돌리기가 적용 전 이력으로 복구한다") {
        val app = openedApp(tempdir())
        app.writeFile("a.txt", "a\n")
        app.stageAndCommit("첫", "a.txt")
        app.writeFile("b.txt", "b\n")
        app.stageAndCommit("둘", "b.txt")
        val beforeApply = app.head()

        val base = RefName("HEAD~2")
        val plan = RebasePlan.of(app.loadRebaseTargets.execute(base)).move(from = 1, to = 0)
        val applied = app.applyRebasePlan.execute(base, plan)

        applied.undoRecordFailure shouldBe null
        app.undoStack.history().first().operation shouldBe GitOperationKind.REBASE

        app.undoAndExpectUndone()

        app.head() shouldBe beforeApply
        app.messagesOldestFirst() shouldContainExactly listOf("initial", "첫", "둘")
    }

    test("push 는 되돌릴 수 없다는 사유와 함께 이력에 남는다") {
        val root = tempdir()
        val bare = File(root, "origin.git").also { it.mkdirs() }
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val work = File(root, "work").also { it.mkdirs() }
        val app = openedApp(work)
        Git.open(work).use { git ->
            git.remoteAdd().setName("origin").setUri(URIish(bare.absolutePath)).call()
            // 업스트림이 없으면 push 는 "어디로 보낼지 모른다" 로 거부한다 — 셋업이 대상을 확정한다.
            git.repository.config.apply {
                setString("branch", "main", "remote", "origin")
                setString("branch", "main", "merge", "refs/heads/main")
                save()
            }
        }

        val outcome = app.pushRemote.execute(mainRef(), force = false) { }

        outcome.undoRecordFailure shouldBe null
        val entry = app.undoStack.history().single()
        entry.operation shouldBe GitOperationKind.PUSH
        entry.irreversibleReason.shouldNotBeNull()

        // 되돌릴 수 없는 최상단은 실행 대상이 아니라 사유와 함께 막힌다.
        app.peekUndoTarget.execute().shouldBeInstanceOf<UndoTarget.Blocked>()
    }
})

/** `feature` 가 `main` 과 다른 파일을 고친 상태를 만든다. 반환 시점의 체크아웃은 `main` 이다. */
private suspend fun divergeFeature(app: ScenarioApp) {
    Git.open(app.work).use { git ->
        git.branchCreate().setName(FEATURE).call()
        git.checkout().setName(FEATURE).call()
    }
    app.writeFile("feature.txt", "기능\n")
    app.stageAndCommit("기능을 추가한다", "feature.txt")
    app.checkoutBranch(mainRef())
    app.writeFile("main.txt", "본선\n")
    app.stageAndCommit("본선을 고친다", "main.txt")
}

/** 같은 파일을 서로 다르게 고쳐 병합 충돌로 멈춘 상태를 만든다. */
private suspend fun conflictingMerge(app: ScenarioApp) {
    app.writeFile(SHARED, "base\n")
    app.stageAndCommit("공유 파일", SHARED)
    Git.open(app.work).use { git ->
        git.branchCreate().setName(FEATURE).call()
        git.checkout().setName(FEATURE).call()
    }
    app.writeFile(SHARED, "그쪽\n")
    app.stageAndCommit("그쪽이 고친다", SHARED)
    app.checkoutBranch(mainRef())
    app.writeFile(SHARED, "우리\n")
    app.stageAndCommit("우리가 고친다", SHARED)

    val beforeMergeHistory = app.undoStack.history()
    app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = false)
        .result.shouldBeInstanceOf<MergeResult.Conflicted>()
    // 시작한 병합은 아직 끝나지 않아 기록 대상이 아니다 — 이력이 그대로여야 한다.
    app.undoStack.history() shouldContainExactly beforeMergeHistory
}

/**
 * `feature` 에 **깨끗하게 적용되는 커밋**과 **충돌하는 커밋**을 차례로 만든다.
 * 반환 시점의 체크아웃은 `main` 이고, `main` 은 같은 파일을 다르게 고친 뒤다.
 *
 * @return (깨끗한 커밋, 충돌하는 커밋)
 */
private suspend fun pickableCommits(app: ScenarioApp): Pair<CommitId, CommitId> {
    app.writeFile(SHARED, "base\n")
    app.stageAndCommit("공유 파일", SHARED)
    Git.open(app.work).use { git ->
        git.branchCreate().setName(FEATURE).call()
        git.checkout().setName(FEATURE).call()
    }
    app.writeFile("clean.txt", "깨끗\n")
    val clean = app.stageAndCommit("겹치지 않는 커밋", "clean.txt")
    app.writeFile(SHARED, "그쪽\n")
    val conflicting = app.stageAndCommit("그쪽이 고친다", SHARED)
    app.checkoutBranch(mainRef())
    app.writeFile(SHARED, "우리\n")
    app.stageAndCommit("우리가 고친다", SHARED)
    return clean to conflicting
}

/** 지금 중단이 지울 편집을 그대로 확인한 값. 화면이 목록을 보여 주고 받는 확인을 재현한다. */
private suspend fun ScenarioApp.discardConfirmation(): CherryPickAbortConfirmation {
    val status = loadStatus.execute()
    val discarded = (status.staged.map { it.path } + status.unstaged.map { it.path } + status.conflicted)
        .distinct()
        .sorted()
    return CherryPickAbortConfirmation.ofDiscardedPaths(discarded)
}
