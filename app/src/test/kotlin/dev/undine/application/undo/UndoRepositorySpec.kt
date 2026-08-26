package dev.undine.application.undo

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.StashEntry
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoOutcome
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.infrastructure.git.ref.RefGatewayImpl
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryGatewayImpl
import dev.undine.infrastructure.git.worktreeops.WorktreeOpsGatewayImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

private const val MAIN_BRANCH = "main"
private const val FEATURE_BRANCH = "feature"
private const val FILE_NAME = "app.txt"
private const val OTHER_FILE_NAME = "notes.txt"

/** 커밋 시각·작성자를 고정한다 — 실행 시각에 결과가 흔들리면 안 된다 (testing 규칙 7). */
private val FIXED_IDENT = PersonIdent(
    "Undine Test",
    "test@undine.dev",
    Instant.parse("2026-01-02T03:04:05Z"),
    ZoneOffset.UTC,
)

private fun initRepository(directory: File): Git =
    Git.init().setDirectory(directory).setInitialBranch(MAIN_BRANCH).call()

private fun Git.commitFile(name: String, content: String, message: String): String {
    File(repository.workTree, name).writeText(content)
    add().addFilepattern(name).call()
    return commit()
        .setMessage(message)
        .setAuthor(FIXED_IDENT)
        .setCommitter(FIXED_IDENT)
        .call()
        .name
}

/**
 * 기록 시점의 stash 항목. 되돌리기가 대상을 다시 찾을 때 쓰는 것은 [StashEntry.target] 뿐이고
 * 나머지 필드는 화면 표시용이라 고정값으로 채운다.
 */
private fun stashEntryOf(target: String) = StashEntry(
    index = 0,
    message = "WIP",
    target = CommitId.of(target),
    createdAt = FIXED_IDENT.whenAsInstant,
    includedUntracked = false,
)

private fun Git.createStash(): String = stashCreate().setPerson(FIXED_IDENT).call().name

private fun Repository.headCommit(): String = resolve("HEAD").name

private fun Repository.refNames(): Set<String> = refDatabase.refs.map { it.name }.toSet()

/**
 * 실제 임시 저장소를 여는 되돌리기 조합. 새 GatewayImpl 은 만들지 않고 기존 구현을 그대로 쓴다 —
 * UND-38 은 domain 규칙과 application 조합만 소유한다.
 */
private class UndoHarness(private val directory: File) {

    private val gitAccess = GitAccess()
    val stack = UndoStack()
    private val refGateway = RefGatewayImpl(gitAccess)
    private val worktreeOpsGateway = WorktreeOpsGatewayImpl(gitAccess)
    private val repositoryGateway = RepositoryGatewayImpl(gitAccess)

    val recorder = OperationRecorder(refGateway, stack)
    val service = UndoService(stack, refGateway, repositoryGateway, worktreeOpsGateway)

    /**
     * 화면과 같은 순서로 되돌린다 — 미리 본 최상단을 **그대로 대상으로 지목**해 넘긴다.
     * 인자 없는 "마지막 것 되돌리기" 는 더 이상 없다 (wave 8 결정 G4).
     */
    suspend fun undoTop(): UndoOutcome {
        val expected = requireNotNull(stack.peek()) { "되돌릴 기록이 없습니다" }
        val execution = service.undo(expected)
        return (execution as UndoExecution.Completed).outcome
    }

    suspend fun <T> use(block: suspend UndoHarness.() -> T): T {
        gitAccess.open(RepositoryPath(directory.absolutePath)) { }
        return try {
            block()
        } finally {
            gitAccess.close()
        }
    }
}

/** 준비·검증용 핸들. 되돌리기 실행과 겹치지 않도록 열고 바로 닫는다. */
private fun <T> inRepository(directory: File, block: (Git) -> T): T =
    Git.open(directory).use(block)

/**
 * 되돌리기를 **실제 JGit 저장소**로 검증한다. domain 규칙만 대역으로 보면
 * "reset 이 정말 그 커밋으로 갔는가" 를 증명할 수 없다 (testing 규칙 1).
 */
class UndoRepositorySpec : FunSpec({

    test("커밋을 되돌리면 직전 커밋으로 soft reset 되고 변경은 스테이징에 남는다") {
        val directory = tempdir()
        val parent = initRepository(directory).use { git ->
            val first = git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.commitFile(FILE_NAME, "two\n", "둘째 커밋")
            first
        }

        val outcome = UndoHarness(directory).use {
            recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(CommitId.of(parent)))
            undoTop()
        }

        outcome.shouldBeInstanceOf<UndoOutcome.Undone>().operation shouldBe GitOperationKind.COMMIT
        inRepository(directory) { git ->
            git.repository.headCommit() shouldBe parent
            // soft reset 이므로 되돌린 커밋의 내용이 워킹트리·인덱스에 남아 있다.
            File(directory, FILE_NAME).readText() shouldBe "two\n"
            git.status().call().changed.contains(FILE_NAME) shouldBe true
        }
    }

    test("브랜치 생성을 되돌리면 그 브랜치가 사라진다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.branchCreate().setName(FEATURE_BRANCH).call()
        }

        val outcome = UndoHarness(directory).use {
            recorder.record(GitOperationKind.BRANCH_CREATE, UndoStrategy.DeleteBranch(RefName(FEATURE_BRANCH)))
            undoTop()
        }

        outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
        inRepository(directory) { git ->
            git.repository.exactRef("refs/heads/$FEATURE_BRANCH") shouldBe null
        }
    }

    test("병합을 되돌리면 기록된 ORIG_HEAD 로 복구된다") {
        val directory = tempdir()
        val beforeMerge = initRepository(directory).use { git ->
            git.commitFile(FILE_NAME, "base\n", "base")
            git.branchCreate().setName(FEATURE_BRANCH).call()
            git.checkout().setName(FEATURE_BRANCH).call()
            git.commitFile(OTHER_FILE_NAME, "feature\n", "feature 작업")
            git.checkout().setName(MAIN_BRANCH).call()
            val head = git.commitFile(FILE_NAME, "main\n", "main 작업")
            git.merge()
                .include(git.repository.exactRef("refs/heads/$FEATURE_BRANCH"))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setMessage("병합")
                .call()
            head
        }

        val outcome = UndoHarness(directory).use {
            recorder.record(GitOperationKind.MERGE, UndoStrategy.HardResetTo(CommitId.of(beforeMerge)))
            undoTop()
        }

        outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
        inRepository(directory) { git ->
            git.repository.headCommit() shouldBe beforeMerge
            File(directory, OTHER_FILE_NAME).exists() shouldBe false
        }
    }

    test("stash 저장을 되돌리면 기록한 stash 가 워킹트리로 돌아온다") {
        val directory = tempdir()
        val stashed = initRepository(directory).use { git ->
            git.commitFile(FILE_NAME, "committed\n", "첫 커밋")
            File(directory, FILE_NAME).writeText("작업 중\n")
            git.createStash()
        }
        File(directory, FILE_NAME).readText() shouldBe "committed\n"

        val outcome = UndoHarness(directory).use {
            recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(stashEntryOf(stashed)))
            undoTop()
        }

        outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
        File(directory, FILE_NAME).readText() shouldBe "작업 중\n"
        inRepository(directory) { git -> git.stashList().call().toList().shouldBeEmpty() }
    }

    test("기록 뒤 밖에서 stash 가 하나 더 쌓여도 기록한 stash 만 풀고 지운다") {
        val directory = tempdir()
        val recordedStash = initRepository(directory).use { git ->
            git.commitFile(FILE_NAME, "committed\n", "첫 커밋")
            git.commitFile(OTHER_FILE_NAME, "notes\n", "둘째 커밋")
            File(directory, FILE_NAME).writeText("앱에서 한 작업\n")
            git.createStash()
        }

        val harness = UndoHarness(directory)
        harness.use {
            recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(stashEntryOf(recordedStash)))
        }
        // 밖에서 stash 를 하나 더 쌓는다. stash 는 브랜치도 HEAD 도 옮기지 않아
        // 기준 상태 비교로는 이 변화를 잡지 못한다 — 그래서 대상을 기록해 둬야 한다.
        val externalStash = inRepository(directory) { git ->
            File(directory, OTHER_FILE_NAME).writeText("밖에서 한 작업\n")
            git.createStash()
        }

        val outcome = harness.use { undoTop() }

        outcome.shouldBeInstanceOf<UndoOutcome.Undone>()
        File(directory, FILE_NAME).readText() shouldBe "앱에서 한 작업\n"
        // 밖에서 만든 stash 는 워킹트리에 풀리지도, 목록에서 사라지지도 않는다.
        File(directory, OTHER_FILE_NAME).readText() shouldBe "notes\n"
        inRepository(directory) { git ->
            git.stashList().call().map { it.name } shouldBe listOf(externalStash)
        }
    }

    test("기록한 stash 가 밖에서 사라졌으면 다른 stash 를 건드리지 않고 대상 없음으로 멈춘다") {
        val directory = tempdir()
        val recordedStash = initRepository(directory).use { git ->
            git.commitFile(FILE_NAME, "committed\n", "첫 커밋")
            git.commitFile(OTHER_FILE_NAME, "notes\n", "둘째 커밋")
            File(directory, FILE_NAME).writeText("앱에서 한 작업\n")
            git.createStash()
        }

        val harness = UndoHarness(directory)
        harness.use {
            recorder.record(GitOperationKind.STASH_PUSH, UndoStrategy.PopStash(stashEntryOf(recordedStash)))
        }
        // 밖에서 기록한 stash 를 지우고 다른 stash 를 남겨 둔다.
        val survivor = inRepository(directory) { git ->
            git.stashDrop().setStashRef(0).call()
            File(directory, OTHER_FILE_NAME).writeText("밖에서 한 작업\n")
            git.createStash()
        }

        val thrown = harness.use { shouldThrow<UndineException.NotFound> { undoTop() } }

        thrown.kind shouldBe UndineException.NotFound.Kind.STASH
        thrown.name shouldBe recordedStash
        // 남아 있던 stash 를 대신 풀거나 지우지 않았다.
        File(directory, OTHER_FILE_NAME).readText() shouldBe "notes\n"
        inRepository(directory) { git ->
            git.stashList().call().map { it.name } shouldBe listOf(survivor)
        }
    }

    test("push·hard reset·stash 삭제는 복구 불가로 남고 저장소를 건드리지 않는다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile(FILE_NAME, "one\n", "첫 커밋") }
        val (headBefore, refsBefore) = inRepository(directory) { git ->
            git.repository.headCommit() to git.repository.refNames()
        }

        val outcomes = UndoHarness(directory).use {
            recorder.recordIrreversible(GitOperationKind.HARD_RESET, "hard reset 이 지운 변경은 남아 있지 않습니다")
            recorder.recordIrreversible(GitOperationKind.STASH_DROP, "지운 stash 는 되살릴 수 없습니다")
            recorder.recordIrreversible(GitOperationKind.PUSH, "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다")
            listOf(undoTop(), undoTop(), undoTop())
        }

        outcomes.map { it.shouldBeInstanceOf<UndoOutcome.Irreversible>().operation } shouldBe listOf(
            GitOperationKind.PUSH,
            GitOperationKind.STASH_DROP,
            GitOperationKind.HARD_RESET,
        )
        outcomes.first().shouldBeInstanceOf<UndoOutcome.Irreversible>().reason shouldContain "원격"
        inRepository(directory) { git ->
            git.repository.headCommit() shouldBe headBefore
            git.repository.refNames() shouldBe refsBefore
        }
    }

    test("기록 뒤 저장소가 밖에서 바뀌면 되돌리지 않고 Git 상태를 그대로 둔다") {
        val directory = tempdir()
        val parent = initRepository(directory).use { git ->
            val first = git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.commitFile(FILE_NAME, "two\n", "둘째 커밋")
            first
        }

        val harness = UndoHarness(directory)
        harness.use {
            recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(CommitId.of(parent)))
        }
        // 앱 밖에서 커밋을 하나 더 쌓는다 — 터미널에서 작업한 상황이다.
        val externalHead = inRepository(directory) { git ->
            git.commitFile(FILE_NAME, "three\n", "밖에서 만든 커밋")
        }

        val outcome = harness.use { undoTop() }

        outcome.shouldBeInstanceOf<UndoOutcome.ExternalChange>()
        inRepository(directory) { git -> git.repository.headCommit() shouldBe externalHead }
    }

    test("detached HEAD 에서는 되돌리지 않는다") {
        val directory = tempdir()
        val parent = initRepository(directory).use { git ->
            val first = git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.commitFile(FILE_NAME, "two\n", "둘째 커밋")
            git.checkout().setName(git.repository.headCommit()).call()
            first
        }

        val outcome = UndoHarness(directory).use {
            recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(CommitId.of(parent)))
            undoTop()
        }

        outcome.shouldBeInstanceOf<UndoOutcome.NoCurrentBranch>().reason shouldContain "detached"
    }

    test("되돌리기 이력은 저장소에 커밋도 ref 도 남기지 않는다") {
        val directory = tempdir()
        val parent = initRepository(directory).use { git ->
            val first = git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.commitFile(FILE_NAME, "two\n", "둘째 커밋")
            first
        }
        val refsBefore = inRepository(directory) { git -> git.repository.refNames() }

        UndoHarness(directory).use {
            recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(CommitId.of(parent)))
            recorder.recordIrreversible(GitOperationKind.PUSH, "원격에 올라간 커밋은 앱이 되돌릴 수 없습니다")
            undoTop()
            undoTop()
        }

        inRepository(directory) { git ->
            // undo 는 HEAD 를 옮기지만 이력을 담는 새 ref 를 만들지 않는다.
            git.repository.refNames() shouldBe refsBefore
            git.repository.headCommit() shouldBe parent
        }
    }

    test("새 세션의 스택에는 이전 세션의 이력이 없다") {
        val directory = tempdir()
        val parent = initRepository(directory).use { git ->
            val first = git.commitFile(FILE_NAME, "one\n", "첫 커밋")
            git.commitFile(FILE_NAME, "two\n", "둘째 커밋")
            first
        }

        val previousEntry = UndoHarness(directory).use {
            recorder.record(GitOperationKind.COMMIT, UndoStrategy.SoftResetTo(CommitId.of(parent))).also {
                stack.peek() shouldNotBe null
            }
        }

        val nextSession = UndoHarness(directory)
        nextSession.stack.history().shouldBeEmpty()
        // 이전 세션의 기록을 그대로 들이밀어도 이 세션의 최상단이 아니므로 아무것도 하지 않는다.
        nextSession.use { service.undo(previousEntry) } shouldBe UndoExecution.TargetChanged
        inRepository(directory) { git -> git.repository.headCommit() shouldNotBe parent }
    }
})
