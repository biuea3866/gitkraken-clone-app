package dev.undine.infrastructure.git.cherrypick

import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositoryState
import dev.undine.domain.UndineException
import dev.undine.domain.cherrypick.CherryPickAbortConfirmation
import dev.undine.domain.cherrypick.CherryPickStep
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

private const val MAIN = "main"
private const val TOPIC = "topic"
private const val SHARED = "shared.txt"
private const val EXTRA = "extra.txt"

private val IDENT = PersonIdent("undine", "undine@example.invalid")

/**
 * cherry-pick Gateway — **실제 커밋을 쌓은 임시 저장소**로 검증한다.
 *
 * JGit 을 Mock 으로 대체하면 "빈 커밋이 되는 경우 JGit 이 OK 를 준다" 같은 실제 동작을 검증하지
 * 못한다 ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class CherryPickGatewayImplSpec : FunSpec({

    test("커밋 하나를 가져오면 현재 브랜치에 새 커밋이 생긴다") {
        val work = tempdir().also { seedTopic(it) }
        val gateway = gatewayFor(work)
        val target = commitOf(work, "토픽 변경")

        val step = gateway.apply(target, recordOrigin = false)

        val created = step.shouldBeInstanceOf<CherryPickStep.Created>().commit
        // 만들어진 커밋이 지금 HEAD 다. 부모·트리·작성자가 원본과 같으면 해시까지 같을 수 있어
        // "원본과 다른 해시" 를 단정하지 않는다 — git 이 그렇게 동작한다.
        created.value shouldBe headOf(work)
        messagesOf(work).last() shouldBe "토픽 변경"
        File(work, EXTRA).readText() shouldBe "토픽\n"
    }

    test("원본 기록 옵션을 켜면 메시지에 원본 해시가 남는다") {
        val work = tempdir().also { seedTopic(it) }
        val gateway = gatewayFor(work)
        val target = commitOf(work, "토픽 변경")

        gateway.apply(target, recordOrigin = true)

        val message = messagesOf(work).last()
        message shouldContain "토픽 변경"
        // 나중에 "이 커밋이 어디서 왔는지" 를 추적하는 유일한 단서다.
        message shouldContain "cherry picked from commit $target"
    }

    test("이미 적용된 변경을 다시 가져오면 커밋을 만들지 않는다") {
        val work = tempdir().also { seedTopic(it) }
        val gateway = gatewayFor(work)
        val target = commitOf(work, "토픽 변경")
        gateway.apply(target, recordOrigin = false)
        val before = messagesOf(work)

        val step = gateway.apply(target, recordOrigin = false)

        // 빈 커밋을 만들지도, 실패하지도 않는다.
        step shouldBe CherryPickStep.Empty
        messagesOf(work) shouldContainExactly before
    }

    test("이력 순서 정렬은 선택 순서를 무시한다") {
        val work = tempdir().also { seedTopic(it, extraCommits = listOf("둘째", "셋째")) }
        val gateway = gatewayFor(work)
        val first = commitOf(work, "토픽 변경")
        val second = commitOf(work, "둘째")
        val third = commitOf(work, "셋째")

        gateway.orderOldestFirst(listOf(third, first, second)) shouldContainExactly
            listOf(first, second, third)
    }

    test("충돌하면 실패가 아니라 충돌 결과를 주고 진행 중으로 남는다") {
        val work = tempdir().also { seedConflictingTopic(it) }
        val gateway = gatewayFor(work)
        val target = commitOf(work, "토픽이 공유 파일을 고친다")

        val step = gateway.apply(target, recordOrigin = false)

        step.shouldBeInstanceOf<CherryPickStep.Conflicted>().paths shouldContainExactly listOf(SHARED)
        gateway.repositoryState() shouldBe RepositoryState.CHERRY_PICKING
        // 어느 커밋을 적용하다 멈췄는지 알려준다.
        gateway.stoppedAt() shouldBe target
    }

    test("충돌을 해결하고 이어가면 커밋이 만들어진다") {
        val work = tempdir().also { seedConflictingTopic(it) }
        val gateway = gatewayFor(work)
        val target = commitOf(work, "토픽이 공유 파일을 고친다")
        gateway.apply(target, recordOrigin = false)

        // 화면이 하는 그대로: 해결한 내용을 쓰고 인덱스에 올린다.
        File(work, SHARED).writeText("합친 결과\n")
        Git.open(work).use { git -> git.add().addFilepattern(SHARED).call() }

        val step = gateway.continueAfterResolve()

        step.shouldBeInstanceOf<CherryPickStep.Created>()
        gateway.repositoryState() shouldBe RepositoryState.NORMAL
        gateway.stoppedAt().shouldBeNull()
        File(work, SHARED).readText() shouldBe "합친 결과\n"
    }

    test("해결하지 않은 채 이어가면 충돌 결과가 그대로 온다") {
        val work = tempdir().also { seedConflictingTopic(it) }
        val gateway = gatewayFor(work)
        gateway.apply(commitOf(work, "토픽이 공유 파일을 고친다"), recordOrigin = false)

        gateway.continueAfterResolve()
            .shouldBeInstanceOf<CherryPickStep.Conflicted>()
            .paths shouldContainExactly listOf(SHARED)
    }

    test("중단하면 시작 전 커밋과 워킹트리로 돌아온다") {
        val work = tempdir().also { seedConflictingTopic(it) }
        val gateway = gatewayFor(work)
        val startPoint = headOf(work)
        gateway.apply(commitOf(work, "토픽이 공유 파일을 고친다"), recordOrigin = false)

        gateway.abort(CherryPickAbortConfirmation.ofDiscardedPaths(listOf(SHARED)))

        headOf(work) shouldBe startPoint
        File(work, SHARED).readText() shouldBe "본선\n"
        gateway.repositoryState() shouldBe RepositoryState.NORMAL
    }

    test("진행 중이 아니면 계속·중단이 상태 위반이다") {
        val work = tempdir().also { seedTopic(it) }
        val gateway = gatewayFor(work)

        shouldThrow<UndineException.StateViolation> { gateway.continueAfterResolve() }
        shouldThrow<UndineException.StateViolation> {
            gateway.abort(CherryPickAbortConfirmation.ofDiscardedPaths(emptyList()))
        }
    }

    test("없는 커밋을 가져오려 하면 NotFound 다") {
        val work = tempdir().also { seedTopic(it) }

        shouldThrow<UndineException.NotFound> {
            gatewayFor(work).apply(CommitId.of("f".repeat(HASH_LENGTH)), recordOrigin = false)
        }
    }
})

private const val HASH_LENGTH = 40

/** `main` 에 서 있고 `topic` 에 가져올 커밋이 쌓인 저장소. */
private fun seedTopic(work: File, extraCommits: List<String> = emptyList()) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        git.configureIdentity()
        File(work, SHARED).writeText("본선\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(TOPIC).call()
        File(work, EXTRA).writeText("토픽\n")
        git.add().addFilepattern(EXTRA).call()
        git.commit().setMessage("토픽 변경").setAuthor(IDENT).setCommitter(IDENT).call()
        extraCommits.forEach { message ->
            File(work, "$message.txt").writeText("$message\n")
            git.add().addFilepattern("$message.txt").call()
            git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT).call()
        }

        git.checkout().setName(MAIN).call()
    }
}

/** `main` 과 `topic` 이 같은 파일을 다르게 고쳐 cherry-pick 이 충돌하게 만든다. */
private fun seedConflictingTopic(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        git.configureIdentity()
        File(work, SHARED).writeText("공통\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(TOPIC).call()
        File(work, SHARED).writeText("토픽\n")
        git.add().addFilepattern(SHARED).call()
        git.commit().setMessage("토픽이 공유 파일을 고친다").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setName(MAIN).call()
        File(work, SHARED).writeText("본선\n")
        git.add().addFilepattern(SHARED).call()
        git.commit().setMessage("본선이 공유 파일을 고친다").setAuthor(IDENT).setCommitter(IDENT).call()
    }
}

/**
 * 저장소 로컬 작성자. 전역 설정에 기대면 개발자 머신에서만 통과하고 CI 에서 깨진다 — cherry-pick 의
 * continue 는 앱이 커밋을 만드는 경로다.
 */
private fun Git.configureIdentity() {
    repository.config.apply {
        setString("user", null, "name", IDENT.name)
        setString("user", null, "email", IDENT.emailAddress)
        save()
    }
}

private fun commitOf(work: File, message: String): CommitId =
    Git.open(work).use { git ->
        val found = git.log().all().call().first { it.fullMessage.trim() == message }
        CommitId.of(found.name)
    }

private fun messagesOf(work: File): List<String> =
    Git.open(work).use { git -> git.log().call().map { it.fullMessage.trim() }.reversed() }

private fun headOf(work: File): String =
    Git.open(work).use { git -> git.repository.resolve("HEAD").name }

private suspend fun gatewayFor(work: File): CherryPickGatewayImpl {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return CherryPickGatewayImpl(gitAccess)
}
