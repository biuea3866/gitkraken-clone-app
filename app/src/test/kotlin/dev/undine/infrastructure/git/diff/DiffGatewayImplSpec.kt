package dev.undine.infrastructure.git.diff

import dev.undine.domain.ChangeType
import dev.undine.domain.CommitId
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import java.io.File

private const val MISSING_COMMIT = "1234567890abcdef1234567890abcdef12345678"

/** 변경 파일 목록 계약 — 커밋↔부모 · 인덱스↔HEAD · 워킹트리↔인덱스. */
class DiffGatewayImplSpec : FunSpec({

    val root = tempdir()
    val opened = mutableListOf<Git>()

    afterSpec { opened.forEach { git -> git.close() } }

    fun newRepository(): Git = initRepository(File(root, "repo-${opened.size}")).also { opened += it }

    test("한 줄 수정한 커밋의 변경 파일 목록에 해당 파일이 1건 나온다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\nthree\n")
        git.commitAll("first")
        git.writeFile("a.txt", "one\nTWO\nthree\n")
        val second = git.commitAll("second")

        val changes = git.diffGateway().changedFiles(second.id(), 0)

        changes shouldHaveSize 1
        changes.single().path shouldBe "a.txt"
        changes.single().previousPath shouldBe null
        changes.single().changeType shouldBe ChangeType.MODIFIED
        changes.single().addedLines shouldBe 1
        changes.single().deletedLines shouldBe 1
        changes.single().isBinary shouldBe false
    }

    test("최초 커밋의 diff 는 전체 파일 추가로 계산된다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\n")
        git.writeFile("nested/b.txt", "three\n")
        val first = git.commitAll("first")

        val changes = git.diffGateway().changedFiles(first.id(), 0)

        changes.map { change -> change.path } shouldContainExactly listOf("a.txt", "nested/b.txt")
        changes.map { change -> change.changeType } shouldContainExactly
            listOf(ChangeType.ADDED, ChangeType.ADDED)
        changes.map { change -> change.addedLines } shouldContainExactly listOf(2, 1)
        changes.map { change -> change.deletedLines } shouldContainExactly listOf(0, 0)
    }

    test("파일 이름만 바뀐 변경이 rename 1건으로 보고된다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\ntwo\nthree\nfour\nfive\n")
        git.commitAll("first")
        File(git.repository.workTree, "a.txt").renameTo(File(git.repository.workTree, "b.txt"))
        val renamed = git.commitAll("rename")

        val changes = git.diffGateway().changedFiles(renamed.id(), 0)

        changes shouldHaveSize 1
        changes.single().changeType shouldBe ChangeType.RENAMED
        changes.single().path shouldBe "b.txt"
        changes.single().previousPath shouldBe "a.txt"
    }

    test("인덱스↔HEAD 변경은 changedFilesStaged 가 돌려준다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.commitAll("first")
        git.writeFile("a.txt", "one\ntwo\n")
        git.stageAll()
        val gateway = git.diffGateway()

        val staged = gateway.changedFilesStaged()

        staged shouldHaveSize 1
        staged.single().path shouldBe "a.txt"
        staged.single().changeType shouldBe ChangeType.MODIFIED
        staged.single().addedLines shouldBe 1
        gateway.changedFilesUnstaged() shouldHaveSize 0
    }

    test("워킹트리↔인덱스 변경은 changedFilesUnstaged 가 돌려준다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.commitAll("first")
        git.writeFile("a.txt", "one\ntwo\n")
        val gateway = git.diffGateway()

        val unstaged = gateway.changedFilesUnstaged()

        unstaged shouldHaveSize 1
        unstaged.single().path shouldBe "a.txt"
        unstaged.single().changeType shouldBe ChangeType.MODIFIED
        gateway.changedFilesStaged() shouldHaveSize 0
    }

    test("무시된 파일은 unstaged 목록에 없고 추적되지 않은 파일은 추가로 보고된다") {
        val git = newRepository()
        git.writeFile(".gitignore", "ignored.txt\n")
        git.commitAll("first")
        git.writeFile("ignored.txt", "noise\n")
        git.writeFile("fresh.txt", "new\n")

        val unstaged = git.diffGateway().changedFilesUnstaged()

        unstaged.map { change -> change.path } shouldContainExactly listOf("fresh.txt")
        unstaged.single().changeType shouldBe ChangeType.ADDED
    }

    test("HEAD 가 없는 저장소의 staged 목록은 전체 추가다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.stageAll()

        val staged = git.diffGateway().changedFilesStaged()

        staged shouldHaveSize 1
        staged.single().changeType shouldBe ChangeType.ADDED
        staged.single().addedLines shouldBe 1
    }

    test("병합 커밋은 parentIndex 로 비교 부모를 고른다") {
        val git = newRepository()
        git.writeFile("base.txt", "base\n")
        git.commitAll("base")
        val mainBranch = git.repository.branch
        git.checkout().setCreateBranch(true).setName("side").call()
        git.writeFile("side.txt", "side\n")
        git.commitAll("side")
        git.checkout().setName(mainBranch).call()
        git.writeFile("main.txt", "main\n")
        git.commitAll("main")
        val merged = git.merge().include(git.repository.resolve("side")).setMessage("merge").call()
        val gateway = git.diffGateway()
        val mergeId = CommitId.of(merged.newHead.name)

        gateway.changedFiles(mergeId, 0).map { change -> change.path } shouldContainExactly listOf("side.txt")
        gateway.changedFiles(mergeId, 1).map { change -> change.path } shouldContainExactly listOf("main.txt")
    }

    test("음수 parentIndex 는 호출부 버그이므로 IllegalArgumentException 이다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        val first = git.commitAll("first")

        shouldThrow<IllegalArgumentException> {
            git.diffGateway().changedFiles(first.id(), -1)
        }
    }

    test("부모 수를 넘는 parentIndex 는 IllegalArgumentException 이다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.commitAll("first")
        git.writeFile("a.txt", "two\n")
        val second = git.commitAll("second")

        shouldThrow<IllegalArgumentException> {
            git.diffGateway().changedFiles(second.id(), 1)
        }
    }

    test("최초 커밋에 0 이외 parentIndex 를 주면 IllegalArgumentException 이다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        val first = git.commitAll("first")

        shouldThrow<IllegalArgumentException> {
            git.diffGateway().hunksOf(first.id(), "a.txt", 1)
        }
    }

    test("존재하지 않는 커밋은 NotFound(COMMIT) 으로 번역된다") {
        val git = newRepository()
        git.writeFile("a.txt", "one\n")
        git.commitAll("first")

        val failure = shouldThrow<UndineException.NotFound> {
            git.diffGateway().changedFiles(CommitId.of(MISSING_COMMIT), 0)
        }

        failure.kind shouldBe UndineException.NotFound.Kind.COMMIT
        failure.name shouldBe MISSING_COMMIT
    }
})
