package dev.undine.infrastructure.git.blame

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.blame.BlameResult
import dev.undine.domain.blame.LineRange
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

private const val MAIN = "main"
private const val CODE = "code.txt"
private const val RENAMED = "moved.txt"
private const val BINARY = "logo.bin"
private const val EMPTY = "empty.txt"

private val FIRST_AUTHOR = PersonIdent("첫 작성자", "first@example.invalid")
private val SECOND_AUTHOR = PersonIdent("둘째 작성자", "second@example.invalid")
private val FORMATTER = PersonIdent("포맷만 바꾼 사람", "format@example.invalid")

/**
 * blame Gateway — **실제 커밋을 쌓은 임시 저장소**로 검증한다.
 *
 * blame 은 "누가 이 줄을 썼는가" 를 답하는 기능이라 Mock 으로는 아무것도 검증하지 못한다
 * ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class BlameGatewayImplSpec : FunSpec({

    test("각 줄에 최종 수정 커밋과 작성자가 매핑된다") {
        val work = tempdir().also(::seedTwoAuthors)
        val gateway = gatewayFor(work)

        val result = gateway.blame(CODE, LineRange.whole(), ignoreWhitespace = false)

        val lines = result.shouldBeInstanceOf<BlameResult.Lines>().lines
        lines.map { it.line } shouldContainExactly listOf(1, 2, 3)
        lines.map { it.author.name } shouldContainExactly
            listOf(FIRST_AUTHOR.name, SECOND_AUTHOR.name, FIRST_AUTHOR.name)
        lines.map { it.content } shouldContainExactly listOf("첫 줄", "둘째가 고친 줄", "셋째 줄")
    }

    test("각 줄이 상대 시각·재귀에 필요한 커밋 전체를 들고 온다") {
        // 화면은 커밋 메타데이터를 파일 이력에서 찾지 않는다 — 그 조회는 limit 에 걸리거나 실패한다.
        val work = tempdir().also(::seedTwoAuthors)
        val gateway = gatewayFor(work)

        val result = gateway.blame(CODE, LineRange.whole(), ignoreWhitespace = false)

        val lines = result.shouldBeInstanceOf<BlameResult.Lines>().lines
        lines.map { it.commit.message.trim() } shouldContainExactly
            listOf("처음 만든다", "둘째 줄을 고친다", "처음 만든다")
        // 첫 커밋은 부모가 없고, 그 뒤 커밋은 첫 부모가 있어야 "이 커밋 이전으로" 가 성립한다.
        lines.first().commit.parents.shouldBeEmpty()
        lines[1].commit.parents.single() shouldBe lines.first().commit.id
        // Git 은 초 단위로 저장하므로 초로 맞춰 본다.
        lines[1].commit.committedAt.epochSecond shouldBe SECOND_AUTHOR.whenAsInstant.epochSecond
    }

    test("범위를 지정하면 그 구간만 돌려준다") {
        val work = tempdir().also(::seedTwoAuthors)
        val gateway = gatewayFor(work)

        val result = gateway.blame(CODE, LineRange.of(2, 2), ignoreWhitespace = false)

        val lines = result.shouldBeInstanceOf<BlameResult.Lines>().lines
        lines.map { it.line } shouldContainExactly listOf(2)
        lines.single().author.name shouldBe SECOND_AUTHOR.name
    }

    test("파일 길이를 넘는 범위는 있는 만큼만 준다") {
        val work = tempdir().also(::seedTwoAuthors)
        val gateway = gatewayFor(work)

        val result = gateway.blame(CODE, LineRange.of(3, 99), ignoreWhitespace = false)

        result.shouldBeInstanceOf<BlameResult.Lines>().lines.map { it.line } shouldContainExactly listOf(3)
    }

    test("범위 시작이 파일 끝을 넘으면 빈 결과다") {
        val work = tempdir().also(::seedTwoAuthors)

        gatewayFor(work).blame(CODE, LineRange.of(50, 60), ignoreWhitespace = false)
            .shouldBeInstanceOf<BlameResult.Lines>()
            .lines
            .shouldBeEmpty()
    }

    test("공백 무시를 켜면 들여쓰기만 바꾼 커밋이 blame 을 덮지 않는다") {
        val work = tempdir().also(::seedWhitespaceOnlyChange)
        val gateway = gatewayFor(work)

        val withWhitespace = gateway.blame(CODE, LineRange.whole(), ignoreWhitespace = false)
        val ignoring = gateway.blame(CODE, LineRange.whole(), ignoreWhitespace = true)

        // 포맷 커밋이 모든 줄을 덮은 것이 기본 동작이다.
        withWhitespace.shouldBeInstanceOf<BlameResult.Lines>()
            .lines.map { it.author.name }.distinct() shouldContainExactly listOf(FORMATTER.name)
        // 무시하면 원 작성자가 남는다 — 그러지 않으면 실제 작성자를 찾을 수 없다.
        ignoring.shouldBeInstanceOf<BlameResult.Lines>()
            .lines.map { it.author.name }.distinct() shouldContainExactly listOf(FIRST_AUTHOR.name)
    }

    test("이름이 바뀐 파일의 이력이 rename 지점을 넘어 이어진다") {
        val work = tempdir().also(::seedRenamedFile)
        val gateway = gatewayFor(work)

        val history = gateway.fileHistory(RENAMED, at = null, limit = 10)

        // rename 이후 커밋 + rename 커밋 + 그 이전 커밋까지 이어진다.
        history.map { it.commit.message.trim() } shouldContainExactly
            listOf("이름 바꾼 뒤 고친다", "이름을 바꾼다", "처음 만든다")
        history.map { it.path } shouldContainExactly listOf(RENAMED, RENAMED, CODE)
        history[1].previousPath shouldBe CODE
    }

    test("삭제된 파일도 그 파일이 있던 커밋 기준으로 이력을 조회한다") {
        val work = tempdir().also(::seedDeletedFile)
        val gateway = gatewayFor(work)
        val beforeDelete = commitOf(work, "지우기 전 마지막")

        val history = gateway.fileHistory(CODE, at = beforeDelete, limit = 10)

        history.map { it.commit.message.trim() } shouldContainExactly listOf("지우기 전 마지막", "처음 만든다")
    }

    test("삭제된 파일을 HEAD 기준으로 조회하면 경로를 찾을 수 없다") {
        val work = tempdir().also(::seedDeletedFile)

        // 실패로 알린다 — 빈 목록을 주면 화면이 "이력 없는 파일" 로 오해한다.
        val failure = shouldThrow<UndineException.NotFound> {
            gatewayFor(work).fileHistory(CODE, at = null, limit = 10)
        }
        failure.kind shouldBe UndineException.NotFound.Kind.PATH
    }

    test("빈 파일의 blame 은 빈 결과이고 예외가 아니다") {
        val work = tempdir().also(::seedEmptyFile)

        gatewayFor(work).blame(EMPTY, LineRange.whole(), ignoreWhitespace = false)
            .shouldBeInstanceOf<BlameResult.Lines>()
            .lines
            .shouldBeEmpty()
    }

    test("이진 파일은 지원하지 않음으로 명시 반환된다") {
        val work = tempdir().also(::seedBinaryFile)

        gatewayFor(work).blame(BINARY, LineRange.whole(), ignoreWhitespace = false) shouldBe
            BlameResult.Unsupported
    }

    test("없는 경로를 blame 하면 경로 부재로 실패한다") {
        val work = tempdir().also(::seedTwoAuthors)

        shouldThrow<UndineException.NotFound> {
            gatewayFor(work).blame("없는파일.txt", LineRange.whole(), ignoreWhitespace = false)
        }
    }

    test("잘못된 줄 범위는 만들 수 없다") {
        shouldThrow<UndineException.StateViolation> { LineRange.of(0, 5) }
        shouldThrow<UndineException.StateViolation> { LineRange.of(5, 4) }
    }
})

/** 두 작성자가 서로 다른 줄을 쓴 파일. */
private fun seedTwoAuthors(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, CODE).writeText("첫 줄\n둘째 줄\n셋째 줄\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "처음 만든다")

        File(work, CODE).writeText("첫 줄\n둘째가 고친 줄\n셋째 줄\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(SECOND_AUTHOR, "둘째 줄을 고친다")
    }
}

/** 들여쓰기만 바꾼 커밋이 마지막에 오는 파일. */
private fun seedWhitespaceOnlyChange(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, CODE).writeText("한 줄\n두 줄\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "처음 만든다")

        // 내용은 그대로, 앞 공백만 붙인다.
        File(work, CODE).writeText("    한 줄\n    두 줄\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FORMATTER, "들여쓰기만 바꾼다")
    }
}

/** 파일을 만들고 → 이름을 바꾸고 → 다시 고친 이력. */
private fun seedRenamedFile(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, CODE).writeText("내용\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "처음 만든다")

        File(work, CODE).renameTo(File(work, RENAMED))
        git.add().addFilepattern(RENAMED).call()
        git.rm().addFilepattern(CODE).setCached(true).call()
        git.commitAs(FIRST_AUTHOR, "이름을 바꾼다")

        File(work, RENAMED).writeText("내용\n한 줄 더\n")
        git.add().addFilepattern(RENAMED).call()
        git.commitAs(SECOND_AUTHOR, "이름 바꾼 뒤 고친다")
    }
}

/** 파일을 만들고 고친 뒤 지운 이력. */
private fun seedDeletedFile(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, CODE).writeText("내용\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "처음 만든다")

        File(work, CODE).writeText("내용\n더\n")
        git.add().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "지우기 전 마지막")

        git.rm().addFilepattern(CODE).call()
        git.commitAs(FIRST_AUTHOR, "지운다")
    }
}

private fun seedEmptyFile(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, EMPTY).writeText("")
        git.add().addFilepattern(EMPTY).call()
        git.commitAs(FIRST_AUTHOR, "빈 파일을 만든다")
    }
}

private fun seedBinaryFile(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, BINARY).writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        git.add().addFilepattern(BINARY).call()
        git.commitAs(FIRST_AUTHOR, "이진 파일을 만든다")
    }
}

private fun Git.commitAs(author: PersonIdent, message: String) {
    commit().setMessage(message).setAuthor(author).setCommitter(author).call()
}

private fun commitOf(work: File, message: String): CommitId =
    Git.open(work).use { git ->
        CommitId.of(git.log().all().call().first { it.fullMessage.trim() == message }.name)
    }

private suspend fun gatewayFor(work: File): BlameGatewayImpl {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return BlameGatewayImpl(gitAccess)
}
