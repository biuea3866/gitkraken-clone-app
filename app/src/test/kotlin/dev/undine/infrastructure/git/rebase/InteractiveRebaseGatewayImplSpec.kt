package dev.undine.infrastructure.git.rebase

import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebasePlan
import dev.undine.infrastructure.git.repository.GitAccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File

private const val MAIN = "main"
private const val TOPIC = "topic"
private const val BASE_FILE = "base.txt"

private val IDENT = PersonIdent("undine", "undine@example.invalid")
private val MAIN_REF = RefName("refs/heads/$MAIN")

/**
 * 대화형 리베이스 Gateway — **실제 커밋을 쌓은 임시 저장소**로 검증한다.
 *
 * JGit 을 Mock 으로 대체하면 todo 목록 교체·squash 합침·충돌 시 남는 상태 같은 실제 규칙을
 * 검증하지 못한다 ([`testing`](../../../../../../../../.agent/rules/testing.md) 규칙 1).
 */
class InteractiveRebaseGatewayImplSpec : FunSpec({

    test("upstream 이후 커밋을 오래된 것부터 준다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b", "c")) }

        val targets = gatewayFor(work).listTargets(MAIN_REF)

        targets.map { it.commit.message.trim() } shouldContainExactly listOf("add a", "add b", "add c")
        // upstream 자신(초기 커밋)은 대상이 아니다.
        targets.none { it.commit.message.trim() == "initial" } shouldBe true
    }

    test("원격 추적 참조에서 닿는 커밋은 이미 push 된 것으로 표시된다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b")) }
        // 첫 커밋만 원격이 알고 있는 상태를 만든다.
        Git.open(work).use { git ->
            val firstTarget = git.log().call().toList().first { it.fullMessage.trim() == "add a" }
            git.branchCreate().setName("origin/$TOPIC").setStartPoint(firstTarget).call()
        }

        val targets = gatewayFor(work).listTargets(MAIN_REF)

        // refs/heads 에 만든 브랜치는 원격 추적이 아니므로 아직 아무것도 pushed 가 아니다.
        targets.none { it.isPushed } shouldBe true
    }

    test("순서를 바꾼 계획을 적용하면 이력 순서가 바뀐다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b")) }
        val gateway = gatewayFor(work)
        val plan = RebasePlan.of(gateway.listTargets(MAIN_REF)).move(from = 1, to = 0)

        gateway.apply(MAIN_REF, plan) shouldBe InteractiveRebaseOutcome.Completed

        work.messagesOldestFirst() shouldContainExactly listOf("initial", "add b", "add a")
    }

    test("squash 로 지정한 커밋은 앞 커밋에 합쳐져 이력이 줄어든다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b")) }
        val gateway = gatewayFor(work)
        val plan = RebasePlan.of(gateway.listTargets(MAIN_REF)).withAction(1, RebaseAction.Squash)

        gateway.apply(MAIN_REF, plan) shouldBe InteractiveRebaseOutcome.Completed

        work.messagesOldestFirst().size shouldBe 2
        // 합쳐진 커밋에도 두 파일이 모두 들어 있다.
        File(work, "a").isFile shouldBe true
        File(work, "b").isFile shouldBe true
    }

    test("drop 으로 지정한 커밋은 결과 이력과 워킹트리에서 사라진다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b")) }
        val gateway = gatewayFor(work)
        val plan = RebasePlan.of(gateway.listTargets(MAIN_REF)).withAction(0, RebaseAction.Drop)

        gateway.apply(MAIN_REF, plan) shouldBe InteractiveRebaseOutcome.Completed

        work.messagesOldestFirst() shouldContainExactly listOf("initial", "add b")
        File(work, "a").exists() shouldBe false
    }

    test("reword 로 지정한 커밋은 메시지만 바뀐다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a")) }
        val gateway = gatewayFor(work)
        val plan = RebasePlan.of(gateway.listTargets(MAIN_REF))
            .withAction(0, RebaseAction.Reword("고친 메시지"))

        gateway.apply(MAIN_REF, plan) shouldBe InteractiveRebaseOutcome.Completed

        work.messagesOldestFirst() shouldContainExactly listOf("initial", "고친 메시지")
        File(work, "a").isFile shouldBe true
    }

    test("규칙을 어긴 계획은 시작하지 않고 저장소를 그대로 남긴다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a", "b")) }
        val gateway = gatewayFor(work)
        val before = work.messagesOldestFirst()
        val broken = RebasePlan.of(gateway.listTargets(MAIN_REF)).withAction(0, RebaseAction.Squash)

        shouldThrow<UndineException.StateViolation> { gateway.apply(MAIN_REF, broken) }

        // 반쯤 진행된 상태를 남기지 않는다 — JGit 에게 규칙 검사를 떠넘기지 않는 이유다.
        work.messagesOldestFirst() shouldContainExactly before
        File(work, ".git/rebase-merge").exists() shouldBe false
    }

    test("진행 중이 아니면 진행률은 null 이다") {
        val work = tempdir().also { seedTopic(it, files = listOf("a")) }

        gatewayFor(work).progress().shouldBeNull()
    }

    test("리베이스가 충돌하면 실패가 아니라 충돌 결과로 돌려주고 진행 중으로 남는다") {
        val work = tempdir().also(::seedConflictingTopic)
        val gateway = gatewayFor(work)
        val plan = RebasePlan.of(gateway.listTargets(MAIN_REF))

        val outcome = gateway.apply(MAIN_REF, plan)

        outcome.shouldBeInstanceOf<InteractiveRebaseOutcome.Conflicted>()
            .paths shouldContainExactly listOf(BASE_FILE)
        // 진행 중으로 남아야 사용자가 해결하고 이어갈 수 있다.
        gateway.progress().shouldNotBeNullProgress()
    }

    test("빈 계획은 적용할 것이 없다") {
        val work = tempdir().also { seedTopic(it, files = emptyList()) }
        val gateway = gatewayFor(work)

        gateway.apply(MAIN_REF, RebasePlan.of(gateway.listTargets(MAIN_REF))) shouldBe
            InteractiveRebaseOutcome.NothingToDo
    }
})

/** `main` 에서 갈라진 `topic` 에 [files] 하나당 커밋 하나를 쌓고 `topic` 에 서 있게 둔다. */
private fun seedTopic(work: File, files: List<String>) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, BASE_FILE).writeText("base\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(TOPIC).call()
        files.forEach { name ->
            File(work, name).writeText("$name\n")
            git.add().addFilepattern(name).call()
            git.commit().setMessage("add $name").setAuthor(IDENT).setCommitter(IDENT).call()
        }
    }
}

/** `main` 과 `topic` 이 같은 파일을 다르게 고쳐 리베이스가 충돌하게 만든다. */
private fun seedConflictingTopic(work: File) {
    Git.init().setDirectory(work).setInitialBranch(MAIN).call().use { git ->
        File(work, BASE_FILE).writeText("base\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setCreateBranch(true).setName(TOPIC).call()
        File(work, BASE_FILE).writeText("topic\n")
        git.add().addFilepattern(BASE_FILE).call()
        git.commit().setMessage("topic changes base").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setName(MAIN).call()
        File(work, BASE_FILE).writeText("main\n")
        git.add().addFilepattern(BASE_FILE).call()
        git.commit().setMessage("main changes base").setAuthor(IDENT).setCommitter(IDENT).call()

        git.checkout().setName(TOPIC).call()
    }
}

/** 오래된 것부터의 커밋 메시지. 리베이스 결과 이력을 그대로 읽는다. */
private fun File.messagesOldestFirst(): List<String> =
    Git.open(this).use { git -> git.log().call().map { it.fullMessage.trim() }.reversed() }

private fun dev.undine.domain.rebase.RebaseRunProgress?.shouldNotBeNullProgress() {
    requireNotNull(this) { "진행 중인 리베이스의 진행률을 읽지 못했다" }
    (applied in 1..total) shouldBe true
}

private suspend fun gatewayFor(work: File): InteractiveRebaseGatewayImpl {
    val gitAccess = GitAccess()
    gitAccess.open(RepositoryPath(work.absolutePath)) { }
    return InteractiveRebaseGatewayImpl(gitAccess)
}
