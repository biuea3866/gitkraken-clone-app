package dev.undine.scenario2

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.worktree.WorktreeState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.api.Git
import java.io.File

private const val FEATURE = "feature"
private const val NOTE = "note.md"

/**
 * 2차 시나리오 8 — worktree 추가 → 같은 브랜치 중복 체크아웃 거부 → 제거.
 *
 * 중복 거부가 이 시나리오의 핵심이다. 한 브랜치를 두 디렉터리에 체크아웃하면 두 워킹트리가 같은 ref 를
 * 각자 움직여 이력이 갈린다 — git 자신도 이것을 막는다.
 */
class WorktreeScenario2Spec : FunSpec({

    test("worktree 를 추가하고 같은 브랜치의 중복 체크아웃을 거부한 뒤 제거한다") {
        openedApp { app ->
            app.writeFile(NOTE, "본선 메모\n")
            app.stageAndCommit("본선 커밋", NOTE)
            Git.open(app.work).use { git -> git.branchCreate().setName(FEATURE).call() }

            val first = File(tempdir(), FEATURE)
            val added = app.addWorktree.execute(RepositoryPath(first.absolutePath), RefName(FEATURE))

            added.state shouldBe WorktreeState.LINKED
            added.branch shouldBe RefName("refs/heads/$FEATURE")
            // 커밋된 내용이 실제로 체크아웃돼야 worktree 로 쓸 수 있다.
            File(first, NOTE).readText() shouldBe "본선 메모\n"
            app.undoStack.history().first().operation shouldBe GitOperationKind.WORKTREE_ADD

            val listing = app.loadWorktrees.execute()
            listing.worktrees.map { it.name } shouldContainExactlyInAnyOrder listOf(app.work.name, FEATURE)
            listing.unsupported.shouldBeEmpty()

            // 같은 브랜치를 다른 디렉터리에 다시 체크아웃하면 거부된다.
            val second = File(tempdir(), "other")
            val failure = shouldThrow<UndineException.StateViolation> {
                app.addWorktree.execute(RepositoryPath(second.absolutePath), RefName(FEATURE))
            }
            failure.detail shouldContain FEATURE
            // 거부된 추가는 아무것도 만들지 않는다.
            second.exists() shouldBe false

            app.removeWorktree.execute(FEATURE)

            first.exists() shouldBe false
            File(app.work, ".git/worktrees/$FEATURE").exists() shouldBe false
            app.loadWorktrees.execute().worktrees.map { it.state } shouldContainExactly listOf(WorktreeState.MAIN)
            app.undoStack.history().first().operation shouldBe GitOperationKind.WORKTREE_REMOVE
        }
    }
})
