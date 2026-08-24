package dev.undine.infrastructure.git.conflict

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.conflict.ConflictSide
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

private const val SHARED_FILE = "shared.txt"
private const val BINARY_FILE = "logo.bin"
private const val MAIN = "main"
private const val FEATURE = "feature"

private val IDENT = PersonIdent("undine", "undine@example.invalid")

/**
 * 충돌 Gateway — **실제 충돌을 만든 임시 저장소**로 검증한다.
 *
 * JGit 을 Mock 으로 대체하면 stage 번호·add 의 충돌 해제 동작 같은 실제 규칙을 검증하지 못한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class ConflictGatewayImplSpec : FunSpec({

    test("충돌한 파일을 목록으로 돌려준다") {
        val work = tempdir().also(::seedTextConflict)

        gatewayFor(work).listConflicted().map { it.path } shouldContainExactly listOf(SHARED_FILE)
    }

    test("충돌이 없으면 빈 목록이다") {
        val work = tempdir().also(::seedClean)

        gatewayFor(work).listConflicted().shouldBeEmpty()
    }

    test("표식이 든 워킹트리 내용을 그대로 읽는다") {
        val work = tempdir().also(::seedTextConflict)

        val content = gatewayFor(work).readConflicted(SHARED_FILE)

        // 세 버전을 인덱스에서 재구성하지 않는다 — 지금 파일에 있는 것을 준다.
        content shouldContain "<<<<<<<"
        content shouldContain "ours"
        content shouldContain "theirs"
    }

    test("충돌하지 않은 경로를 읽으려 하면 NotFound 다") {
        val work = tempdir().also(::seedTextConflict)

        shouldThrow<UndineException.NotFound> { gatewayFor(work).readConflicted("other.txt") }
    }

    test("해결하면 워킹트리와 인덱스가 함께 갱신되고 충돌 목록에서 빠진다") {
        val work = tempdir().also(::seedTextConflict)
        val gateway = gatewayFor(work)

        gateway.resolve(SHARED_FILE, "합친 결과\n")

        File(work, SHARED_FILE).readText() shouldBe "합친 결과\n"
        // add 가 stage 1·2·3 을 지우고 stage 0 으로 바꾼다 — 그것이 곧 해결 기록이다.
        gateway.listConflicted().shouldBeEmpty()
        Git.open(work).use { git ->
            git.status().call().changed shouldContainExactly setOf(SHARED_FILE)
        }
    }

    test("이진 파일은 이진으로 표시되고 한쪽을 채택하면 그 내용이 된다") {
        val work = tempdir().also(::seedBinaryConflict)
        val gateway = gatewayFor(work)

        gateway.listConflicted().single().isBinary shouldBe true
        gateway.resolveBinary(BINARY_FILE, ConflictSide.THEIRS)

        File(work, BINARY_FILE).readBytes().toList() shouldBe THEIRS_BYTES.toList()
        gateway.listConflicted().shouldBeEmpty()
    }

    test("ours 를 채택하면 우리 쪽 내용이 된다") {
        val work = tempdir().also(::seedBinaryConflict)

        gatewayFor(work).resolveBinary(BINARY_FILE, ConflictSide.OURS)

        File(work, BINARY_FILE).readBytes().toList() shouldBe OURS_BYTES.toList()
    }
})

private val OURS_BYTES = byteArrayOf(0, 1, 2, 0, 3)
private val THEIRS_BYTES = byteArrayOf(0, 9, 8, 0, 7)

/** 충돌 없는 저장소. */
private fun seedClean(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, SHARED_FILE).writeText("base\n")
        git.add().addFilepattern(SHARED_FILE).call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()
    }
}

/** `shared.txt` 를 양쪽에서 다르게 고쳐 텍스트 충돌을 만든다. */
private fun seedTextConflict(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, SHARED_FILE).writeText("base\n")
        File(work, "other.txt").writeText("untouched\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(FEATURE).call()
        File(work, SHARED_FILE).writeText("theirs\n")
        git.add().addFilepattern(SHARED_FILE).call()
        git.commit().setMessage("theirs").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setName(MAIN).call()
        File(work, SHARED_FILE).writeText("ours\n")
        git.add().addFilepattern(SHARED_FILE).call()
        git.commit().setMessage("ours").setAuthor(IDENT).setCommitter(IDENT).call()

        val merge = git.merge().include(git.repository.findRef(FEATURE)).call()
        merge.mergeStatus shouldBe MergeResult.MergeStatus.CONFLICTING
    }
}

/** 이진 파일을 양쪽에서 다르게 고쳐 충돌을 만든다. */
private fun seedBinaryConflict(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, BINARY_FILE).writeBytes(byteArrayOf(0, 0, 0))
        git.add().addFilepattern(BINARY_FILE).call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(FEATURE).call()
        File(work, BINARY_FILE).writeBytes(THEIRS_BYTES)
        git.add().addFilepattern(BINARY_FILE).call()
        git.commit().setMessage("theirs").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setName(MAIN).call()
        File(work, BINARY_FILE).writeBytes(OURS_BYTES)
        git.add().addFilepattern(BINARY_FILE).call()
        git.commit().setMessage("ours").setAuthor(IDENT).setCommitter(IDENT).call()

        git.merge().include(git.repository.findRef(FEATURE)).call()
    }
}

private suspend fun gatewayFor(work: File): ConflictGatewayImpl {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return ConflictGatewayImpl(gitAccess)
}
