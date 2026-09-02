package dev.undine.scenario2

import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.PushResult
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.UndoOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File

private const val NOTE = "note.md"
private const val ORIGIN = "origin"

/** `PushRemoteUseCase` 가 push 기록에 남기는 사유. 화면이 그대로 보여 주는 문장이다. */
private const val PUSHED_IRREVERSIBLE_REASON =
    "원격에 올라간 변경은 앱이 되돌리지 못합니다 — 원격에서 직접 되돌려야 합니다."

/**
 * 2차 시나리오 6 — 커밋 → undo 로 상태 복구 → push 뒤에는 undo 대상에서 제외.
 *
 * **Undo 의 경계를 확인하는 자리다.** 원격에 올라간 것은 앱이 되돌리지 못하므로, 기록은 남기되
 * 되돌릴 수 있는 대상으로 내주지 않아야 한다 — 조용히 성공한 것처럼 보이면 사용자는 원격이 되돌아간
 * 줄 안다.
 *
 * 원격은 **로컬 파일 경로의 베어 저장소**다. 네트워크를 타면 CI 에서 불안정해진다 (`testing` 규칙 2).
 */
class UndoBoundaryScenario2Spec : FunSpec({

    test("커밋 뒤 undo 는 커밋 직전 상태로 되돌린다") {
        openedApp { app ->
            val before = app.head()

            app.writeFile(NOTE, "메모\n")
            app.stageAndCommit("메모를 남긴다", NOTE)
            app.head() shouldNotBe before

            val outcome = app.undoTop().shouldBeInstanceOf<UndoExecution.Completed>()

            outcome.outcome.shouldBeInstanceOf<UndoOutcome.Undone>().operation shouldBe GitOperationKind.COMMIT
            app.head() shouldBe before
            app.messagesOldestFirst() shouldContainExactly listOf("initial")
            // soft reset 이라 변경 내용은 남는다 — 커밋만 취소된다.
            app.readFile(NOTE) shouldBe "메모\n"
        }
    }

    test("로컬 파일 원격으로 push 가 성공하면 그 기록은 되돌릴 수 없는 항목으로 막힌다") {
        val root = tempdir()
        openedApp(File(root, "work")) { app ->
            app.attachBareRemote(File(root, "origin.git"))

            app.writeFile(NOTE, "원격으로 보낸다\n")
            app.stageAndCommit("메모를 올린다", NOTE)

            val pushed = app.pushRemote.execute(mainRef(), force = false) { }

            pushed.result shouldBe PushResult.Accepted
            pushed.undoRecordFailure shouldBe null

            // 되돌릴 수 없는 연산도 이력에는 남는다 — 남기지 않으면 사용자가 그 동작을 이력에서 보지 못한다.
            val entry = app.undoStack.history().first()
            entry.operation shouldBe GitOperationKind.PUSH
            entry.irreversibleReason.shouldNotBeNull() shouldBe PUSHED_IRREVERSIBLE_REASON

            // 그러나 되돌리기 대상은 아니다 — 사유와 함께 막힌다.
            val blocked = app.peekUndoTarget.execute().shouldBeInstanceOf<UndoTarget.Blocked>()
            blocked.entry shouldBe entry
            blocked.refusal.shouldBeInstanceOf<UndoOutcome.Irreversible>().detail shouldBe PUSHED_IRREVERSIBLE_REASON

            // 실행을 요청해도 저장소를 바꾸지 않고 거부한다.
            val head = app.head()
            val execution = app.undoLastOperation.execute(entry).shouldBeInstanceOf<UndoExecution.Completed>()
            execution.outcome.shouldBeInstanceOf<UndoOutcome.Refused>().reason shouldContain PUSHED_IRREVERSIBLE_REASON
            app.head() shouldBe head
        }
    }
})

/** 로컬 베어 저장소를 원격으로 등록하고 **업스트림까지** 설정한다. 없으면 push 가 대상을 정하지 못한다. */
private fun Scenario2App.attachBareRemote(bare: File) {
    Git.init().setBare(true).setInitialBranch(MAIN_BRANCH).setDirectory(bare).call().use { }
    Git.open(work).use { git ->
        git.remoteAdd().setName(ORIGIN).setUri(URIish(bare.absolutePath)).call()
        git.repository.config.apply {
            setString("branch", MAIN_BRANCH, "remote", ORIGIN)
            setString("branch", MAIN_BRANCH, "merge", "refs/heads/$MAIN_BRANCH")
            save()
        }
    }
}
