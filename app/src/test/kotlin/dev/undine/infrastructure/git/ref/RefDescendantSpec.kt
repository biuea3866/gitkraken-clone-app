package dev.undine.infrastructure.git.ref

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import dev.undine.infrastructure.git.repository.GitAccess
import dev.undine.infrastructure.git.repository.RepositoryHolder
import dev.undine.infrastructure.git.repository.commitFile
import dev.undine.infrastructure.git.repository.initRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git

private const val FILE_NAME = "a.txt"
private val TOPIC = RefName("topic")

/**
 * 커밋 하나가 다른 커밋에서 이어지는지의 판정을 **실제 임시 저장소**로 본다.
 *
 * 이 판정이 지목 pull 의 fast-forward 근거 하나다 (결정 D9) — 여기서 틀리면 호출부가 병합해야 할
 * 상황을 빨리 감기로 착각한다. JGit 을 Mock 으로 대체하지 않는다.
 */
class RefDescendantSpec : FunSpec({

    val openedRepositories = mutableListOf<Git>()

    afterTest {
        openedRepositories.forEach(Git::close)
        openedRepositories.clear()
    }

    suspend fun repository(): Pair<Git, RefGatewayImpl> {
        val git = initRepository(tempdir()).also { openedRepositories += it }
        val gitAccess = GitAccess(RepositoryHolder { git.repository })
        gitAccess.open(RepositoryPath(git.repository.workTree.path)) { }
        return git to RefGatewayImpl(gitAccess)
    }

    test("뒤에 쌓인 커밋은 앞선 커밋의 자손이다") {
        val (git, gateway) = repository()
        val first = CommitId.of(git.commitFile(FILE_NAME, "1\n", "first").name)
        val second = CommitId.of(git.commitFile(FILE_NAME, "2\n", "second").name)

        gateway.isDescendantOf(second, first) shouldBe true
    }

    test("앞선 커밋은 뒤에 쌓인 커밋의 자손이 아니다 — 되감기는 빨리 감기가 아니다") {
        val (git, gateway) = repository()
        val first = CommitId.of(git.commitFile(FILE_NAME, "1\n", "first").name)
        val second = CommitId.of(git.commitFile(FILE_NAME, "2\n", "second").name)

        gateway.isDescendantOf(first, second) shouldBe false
    }

    test("같은 커밋은 자기 자신의 자손이다 — 옮길 것이 없을 뿐 갈라진 것이 아니다") {
        val (git, gateway) = repository()
        val only = CommitId.of(git.commitFile(FILE_NAME, "1\n", "only").name)

        gateway.isDescendantOf(only, only) shouldBe true
    }

    test("갈라진 두 갈래는 서로 자손이 아니고 공통 조상만 조상으로 남는다") {
        val (git, gateway) = repository()
        val base = CommitId.of(git.commitFile(FILE_NAME, "1\n", "base").name)
        git.branchCreate().setName(TOPIC.value).call()
        val onMain = CommitId.of(git.commitFile(FILE_NAME, "main\n", "on main").name)
        git.checkout().setName(TOPIC.value).call()
        val onTopic = CommitId.of(git.commitFile("b.txt", "topic\n", "on topic").name)

        gateway.isDescendantOf(onTopic, onMain) shouldBe false
        gateway.isDescendantOf(onMain, onTopic) shouldBe false
        gateway.isDescendantOf(onTopic, base) shouldBe true
    }

    test("저장소에 없는 커밋을 물으면 없다고 알린다 — false 로 접지 않는다") {
        val (git, gateway) = repository()
        val only = CommitId.of(git.commitFile(FILE_NAME, "1\n", "only").name)
        val absent = CommitId.of("0".repeat(39) + "1")

        shouldThrow<UndineException.NotFound> { gateway.isDescendantOf(absent, only) }
        shouldThrow<UndineException.NotFound> { gateway.isDescendantOf(only, absent) }
    }
})
