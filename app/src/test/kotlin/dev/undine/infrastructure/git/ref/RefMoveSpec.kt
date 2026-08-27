package dev.undine.infrastructure.git.ref

import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
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
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId

private const val FILE_NAME = "a.txt"
private const val MAIN_REF = "refs/heads/main"
private const val TOPIC_REF = "refs/heads/topic"
private const val TAG_REF = "refs/tags/v1"
private val MAIN = RefName("main")
private val TOPIC = RefName("topic")
private val TAG = RefName("v1")

/** `main` 에 두 커밋이 있고 `topic` 은 첫 커밋에 머무는 저장소와, 그 저장소를 보는 Gateway. */
private class RefMoveFixture(val git: Git, val gateway: RefGatewayImpl, val first: CommitId, val second: CommitId)

private fun Git.targetOf(fullRef: String): String = repository.exactRef(fullRef).objectId.name

private fun Git.tagAt(commit: CommitId, annotated: Boolean, message: String?) {
    tag()
        .setName(TAG.value)
        .setObjectId(repository.parseCommit(ObjectId.fromString(commit.value)))
        .setAnnotated(annotated)
        .setMessage(message)
        .call()
}

/**
 * 브랜치·태그 포인터 이동의 **조건부 갱신(CAS)** 을 실제 임시 저장소로 본다 (결정 G2).
 *
 * 기대 위치와 실제 위치가 어긋나면 옮기지 않고 사유와 함께 거부해야 한다 — 강제로 옮기면
 * 그 ref 로만 도달하던 커밋을 잃는다. JGit 을 Mock 으로 대체하지 않는다.
 */
class RefMoveSpec : FunSpec({

    val openedRepositories = mutableListOf<Git>()

    afterTest {
        openedRepositories.forEach(Git::close)
        openedRepositories.clear()
    }

    suspend fun fixture(): RefMoveFixture {
        val git = initRepository(tempdir()).also { openedRepositories += it }
        val first = CommitId.of(git.commitFile(FILE_NAME, "1\n", "first").name)
        git.branchCreate().setName(TOPIC.value).call()
        val second = CommitId.of(git.commitFile(FILE_NAME, "2\n", "second").name)
        val gitAccess = GitAccess(RepositoryHolder { git.repository })
        gitAccess.open(RepositoryPath(git.repository.workTree.path)) { }
        return RefMoveFixture(git, RefGatewayImpl(gitAccess), first, second)
    }

    test("기대 위치를 가리키는 브랜치는 새 커밋으로 옮겨진다") {
        val fixture = fixture()

        fixture.gateway.moveBranch(TOPIC, to = fixture.second, expected = fixture.first)

        fixture.git.targetOf(TOPIC_REF) shouldBe fixture.second.value
    }

    test("기대 위치와 다른 곳을 가리키는 브랜치는 옮기지 않고 사유와 함께 거부한다") {
        val fixture = fixture()

        val failure = shouldThrow<UndineException.StateViolation> {
            fixture.gateway.moveBranch(TOPIC, to = fixture.second, expected = fixture.second)
        }

        failure.detail shouldContain TOPIC.value
        fixture.git.targetOf(TOPIC_REF) shouldBe fixture.first.value
    }

    test("현재 체크아웃된 브랜치는 포인터만 옮기지 않는다 — 워킹트리가 어긋나기 때문이다") {
        val fixture = fixture()

        val failure = shouldThrow<UndineException.StateViolation> {
            fixture.gateway.moveBranch(MAIN, to = fixture.first, expected = fixture.second)
        }

        failure.detail shouldContain MAIN.value
        fixture.git.targetOf(MAIN_REF) shouldBe fixture.second.value
    }

    test("존재하지 않는 브랜치 이동은 NotFound 다") {
        val fixture = fixture()

        shouldThrow<UndineException.NotFound> {
            fixture.gateway.moveBranch(RefName("missing"), to = fixture.second, expected = fixture.first)
        }
    }

    test("lightweight 태그도 같은 조건부 규칙으로 옮겨진다") {
        val fixture = fixture()
        fixture.git.tagAt(fixture.first, annotated = false, message = null)

        fixture.gateway.moveTag(TAG, to = fixture.second, expected = fixture.first)

        fixture.git.targetOf(TAG_REF) shouldBe fixture.second.value
    }

    test("두 이동을 직렬로 하면 첫 결과의 기준 상태에 두 번째 이동이 섞이지 않는다") {
        val fixture = fixture()
        fixture.git.tagAt(fixture.first, annotated = false, message = null)

        // 첫 이동은 HEAD 를 건드리지 않으므로 기준 상태는 체크아웃된 main 그대로다.
        val afterTagMove = fixture.gateway.moveTag(TAG, to = fixture.second, expected = fixture.first)
        // 이어서 앱 안의 두 번째 조작이 main 을 되감는다.
        fixture.git.reset().setMode(ResetCommand.ResetType.HARD).setRef(fixture.first.value).call()
        val afterReset = fixture.gateway.moveBranch(TOPIC, to = fixture.second, expected = fixture.first)

        // 첫 결과는 자기 임계 구역에서 캡처된 값이라 뒤이은 되감기를 알지 못한다 (UND-73).
        afterTagMove shouldBe RepositoryBaseline(branch = MAIN, head = fixture.second)
        afterReset shouldBe RepositoryBaseline(branch = MAIN, head = fixture.first)
    }

    test("detached HEAD 에서 이동하면 기준 상태는 브랜치도 HEAD 도 없는 상태다") {
        val fixture = fixture()
        fixture.git.checkout().setName(fixture.second.value).call()

        val baseline = fixture.gateway.moveBranch(TOPIC, to = fixture.second, expected = fixture.first)

        // 되돌리기는 브랜치 위에서만 허용된다 — 이 값이 그 거부의 근거가 된다.
        baseline shouldBe RepositoryBaseline(branch = null, head = null)
    }

    test("기대 위치와 다른 태그 이동은 거부되고 태그는 그대로 남는다") {
        val fixture = fixture()
        fixture.git.tagAt(fixture.first, annotated = false, message = null)

        val failure = shouldThrow<UndineException.StateViolation> {
            fixture.gateway.moveTag(TAG, to = fixture.second, expected = fixture.second)
        }

        failure.detail shouldContain TAG.value
        fixture.git.targetOf(TAG_REF) shouldBe fixture.first.value
    }

    test("annotated 태그는 메시지·tagger 를 잃으므로 옮기지 않는다") {
        val fixture = fixture()
        fixture.git.tagAt(fixture.first, annotated = true, message = "릴리즈")
        val before = fixture.git.targetOf(TAG_REF)

        shouldThrow<UndineException.StateViolation> {
            fixture.gateway.moveTag(TAG, to = fixture.second, expected = fixture.first)
        }

        fixture.git.targetOf(TAG_REF) shouldBe before
    }
})
