package dev.undine.infrastructure.git.repository

import dev.undine.domain.ChangeType
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseResult
import java.io.File
import java.nio.file.Files

private const val CONFLICT_FILE = "conflict.txt"

/**
 * `RepositoryGatewayImpl` 통합 테스트. 전부 `tempdir()` 의 실제 저장소를 쓴다.
 */
class RepositoryGatewayImplSpec : FunSpec({

    test("정상 저장소를 열면 NORMAL 과 현재 브랜치를 반환한다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gateway = RepositoryGatewayImpl()

        val opened = gateway.open(RepositoryPath(directory.path))

        opened.state shouldBe RepositoryState.NORMAL
        opened.currentBranch?.value shouldBe "refs/heads/$INITIAL_BRANCH"
        gateway.close()
    }

    test("커밋이 0건인 저장소는 EMPTY 로 판정되고 예외를 던지지 않는다") {
        val directory = tempdir()
        initRepository(directory).use { }
        val gateway = RepositoryGatewayImpl()

        val opened = gateway.open(RepositoryPath(directory.path))

        opened.state shouldBe RepositoryState.EMPTY
        opened.currentBranch?.value shouldBe "refs/heads/$INITIAL_BRANCH"
        gateway.close()
    }

    test("detached HEAD 는 DETACHED 로 판정되고 currentBranch 가 null 이다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            val first = git.commitFile("a.txt", "a\n", "first")
            git.commitFile("a.txt", "b\n", "second")
            git.checkout().setName(first.name).call()
        }
        val gateway = RepositoryGatewayImpl()

        val opened = gateway.open(RepositoryPath(directory.path))

        opened.state shouldBe RepositoryState.DETACHED
        opened.currentBranch shouldBe null
        gateway.close()
    }

    test("병합이 충돌한 저장소는 MERGING 으로 판정된다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.createDivergedBranches(CONFLICT_FILE)
            val merge = git.merge().include(git.repository.findRef("feature")).call()
            merge.mergeStatus shouldBe MergeResult.MergeStatus.CONFLICTING
        }
        val gateway = RepositoryGatewayImpl()

        gateway.open(RepositoryPath(directory.path)).state shouldBe RepositoryState.MERGING

        gateway.close()
    }

    test("리베이스가 멈춘 저장소는 REBASING 으로 판정된다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.createDivergedBranches(CONFLICT_FILE)
            git.checkout().setName("feature").call()
            val rebase = git.rebase().setUpstream(INITIAL_BRANCH).call()
            rebase.status shouldBe RebaseResult.Status.STOPPED
        }
        val gateway = RepositoryGatewayImpl()

        gateway.open(RepositoryPath(directory.path)).state shouldBe RepositoryState.REBASING

        gateway.close()
    }

    test("revert 가 충돌한 저장소는 REVERTING 으로 판정된다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.commitFile(CONFLICT_FILE, "one\n", "one")
            val second = git.commitFile(CONFLICT_FILE, "two\n", "two")
            git.commitFile(CONFLICT_FILE, "three\n", "three")
            git.revert().include(second).call()
            File(git.repository.directory, "REVERT_HEAD").exists() shouldBe true
        }
        val gateway = RepositoryGatewayImpl()

        gateway.open(RepositoryPath(directory.path)).state shouldBe RepositoryState.REVERTING

        gateway.close()
    }

    test("존재하지 않는 경로는 NOT_FOUND 로 번역된다") {
        val missing = File(tempdir(), "missing")
        val gateway = RepositoryGatewayImpl()

        val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
            gateway.open(RepositoryPath(missing.path))
        }

        failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.NOT_FOUND
    }

    test("읽을 수 없는 경로는 존재하더라도 PERMISSION_DENIED 로 번역된다") {
        val directory = tempdir()
        val unreadable = File(directory, "unreadable").also { it.mkdirs() }
        Files.setPosixFilePermissions(unreadable.toPath(), emptySet())
        val gateway = RepositoryGatewayImpl()

        try {
            val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
                gateway.open(RepositoryPath(unreadable.path))
            }
            failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.PERMISSION_DENIED
        } finally {
            Files.setPosixFilePermissions(unreadable.toPath(), READ_WRITE_EXECUTE_OWNER)
        }
    }

    test("베어 저장소는 BARE_REPOSITORY 로 거부된다") {
        val directory = tempdir()
        initBareRepository(directory).use { }
        val gateway = RepositoryGatewayImpl()

        val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
            gateway.open(RepositoryPath(directory.path))
        }

        failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.BARE_REPOSITORY
    }

    test("읽을 수 없는 베어 저장소는 BARE_REPOSITORY 보다 PERMISSION_DENIED 가 먼저 걸린다") {
        val unreadableBare = File(tempdir(), "unreadable-bare").also { it.mkdirs() }
        initBareRepository(unreadableBare).use { }
        Files.setPosixFilePermissions(unreadableBare.toPath(), emptySet())
        val gateway = RepositoryGatewayImpl()

        try {
            val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
                gateway.open(RepositoryPath(unreadableBare.path))
            }
            failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.PERMISSION_DENIED
        } finally {
            Files.setPosixFilePermissions(unreadableBare.toPath(), READ_WRITE_EXECUTE_OWNER)
        }
    }

    test("Git 저장소가 아닌 디렉터리는 NOT_A_REPOSITORY 로 번역된다") {
        val directory = tempdir()
        val gateway = RepositoryGatewayImpl()

        val failure = shouldThrow<UndineException.InvalidRepositoryPath> {
            gateway.open(RepositoryPath(directory.path))
        }

        failure.reason shouldBe UndineException.InvalidRepositoryPath.Reason.NOT_A_REPOSITORY
    }

    test("변경이 없는 저장소의 status 는 isClean 이 참이다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))

        val status = gateway.status()

        status.isClean shouldBe true
        status.staged.shouldBeEmpty()
        gateway.close()
    }

    test("status 는 staged 변경을 추가·수정·삭제로 구분한다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.commitFile("kept.txt", "kept\n", "base")
            git.commitFile("removed.txt", "removed\n", "base removed")
            git.writeWorkTreeFile("added.txt", "added\n")
            git.writeWorkTreeFile("kept.txt", "changed\n")
            git.add().addFilepattern("added.txt").addFilepattern("kept.txt").call()
            git.rm().addFilepattern("removed.txt").call()
        }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))

        val status = gateway.status()

        status.staged.map { it.path } shouldContainExactly listOf("added.txt", "kept.txt", "removed.txt")
        status.staged.map { it.changeType } shouldContainExactly
            listOf(ChangeType.ADDED, ChangeType.MODIFIED, ChangeType.DELETED)
        status.isClean shouldBe false
        gateway.close()
    }

    test("status 는 unstaged 수정·삭제와 untracked 파일을 구분한다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.commitFile("modified.txt", "before\n", "base modified")
            git.commitFile("missing.txt", "gone\n", "base missing")
            git.writeWorkTreeFile("modified.txt", "after\n")
            git.deleteWorkTreeFile("missing.txt")
            git.writeWorkTreeFile("untracked.txt", "new\n")
        }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))

        val status = gateway.status()

        status.unstaged.map { it.path } shouldContainExactly listOf("missing.txt", "modified.txt")
        status.unstaged.map { it.changeType } shouldContainExactly listOf(ChangeType.DELETED, ChangeType.MODIFIED)
        status.untracked shouldContainExactly listOf("untracked.txt")
        gateway.close()
    }

    test("충돌한 파일은 conflicted 목록에 담긴다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.createDivergedBranches(CONFLICT_FILE)
            git.merge().include(git.repository.findRef("feature")).call()
        }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))

        val status = gateway.status()

        status.conflicted shouldContainExactly listOf(CONFLICT_FILE)
        status.isClean shouldBe false
        gateway.close()
    }

    test("줄 수·이진 판정·이전 경로는 UND-05 범위라 기본값으로 채운다") {
        val directory = tempdir()
        initRepository(directory).use { git ->
            git.commitFile("a.txt", "a\n", "base")
            git.writeWorkTreeFile("a.txt", "a\nb\n")
        }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))

        val change = gateway.status().unstaged.single()

        change.addedLines shouldBe 0
        change.deletedLines shouldBe 0
        change.isBinary shouldBe false
        change.previousPath shouldBe null
        gateway.close()
    }

    test("열기 전 status 호출은 StateViolation 을 던진다") {
        val gateway = RepositoryGatewayImpl()

        val failure = shouldThrow<UndineException.StateViolation> { gateway.status() }

        failure.detail shouldBe "저장소가 열려 있지 않습니다"
    }

    test("close 후 status 호출은 StateViolation 을 던진다") {
        val directory = tempdir()
        initRepository(directory).use { git -> git.commitFile("a.txt", "a\n", "first") }
        val gateway = RepositoryGatewayImpl()
        gateway.open(RepositoryPath(directory.path))
        gateway.close()

        shouldThrow<UndineException.StateViolation> { gateway.status() }
    }
})
