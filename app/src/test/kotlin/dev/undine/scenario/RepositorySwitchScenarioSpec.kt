package dev.undine.scenario

import dev.undine.domain.RepositoryPath
import dev.undine.domain.UndineException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private const val NOTE = "note.md"

/**
 * 시나리오 8 — 저장소 열기 → 전환 → 이전 저장소 자원 해제.
 *
 * 핸들이 남아 있는지를 **관찰 가능한 결과**로 확인한다: 닫힌 세션에서의 조회는 빈 결과가 아니라
 * 실패여야 하고, 전환한 뒤의 조회는 새 저장소의 이력이어야 한다. "핸들 0" 을 세는 대신 이렇게 보는
 * 이유는 JGit 이 핸들 수를 외부에 노출하지 않기 때문이다.
 */
class RepositorySwitchScenarioSpec : FunSpec({

    test("저장소를 전환하면 이후 조회가 새 저장소만 본다") {
        val root = tempdir()
        val first = seedWith(File(root, "first"), "첫 저장소")
        val second = seedWith(File(root, "second"), "둘째 저장소")
        val app = ScenarioApp(first)

        app.open()
        app.messagesOldestFirst() shouldContainExactly listOf("initial", "첫 저장소")

        // 같은 세션에서 다른 경로를 열면 이전 핸들은 닫히고 새 핸들로 바뀐다.
        app.openRepository.execute(RepositoryPath(second.absolutePath))

        // 조회 대상이 새 저장소다 — 이전 저장소의 이력이 섞여 나오지 않는다.
        val history = app.loadHistory.execute(listOf(mainRef()), offset = 0, limit = 10)
        history.map { it.message.trim() } shouldContainExactly listOf("둘째 저장소", "initial")
    }

    test("닫은 뒤의 조회는 빈 결과가 아니라 실패다") {
        val app = ScenarioApp(seedWith(tempdir(), "커밋"))
        app.open()
        app.loadStatus.execute().isClean shouldBe true

        app.close()

        // 빈 결과를 주면 화면이 "변경 없음" 으로 오해한다 — 그래서 실패로 알린다.
        val failure = shouldThrow<UndineException.StateViolation> { app.loadStatus.execute() }
        failure.message.orEmpty() shouldContain "열려 있지"
    }

    test("닫은 세션도 다시 열면 그대로 동작한다") {
        val app = ScenarioApp(seedWith(tempdir(), "커밋"))
        app.open()
        app.close()

        app.open()

        app.writeFile(NOTE, "다시 열고 작업\n")
        app.stageAndCommit("다시 연 뒤 커밋", NOTE)
        app.messagesOldestFirst() shouldContainExactly listOf("initial", "커밋", "다시 연 뒤 커밋")
    }
})

/** 커밋 두 개(초기 + [message])가 있는 저장소를 만든다. */
private fun seedWith(work: File, message: String): File {
    require(work.exists() || work.mkdirs()) { "임시 디렉토리를 만들지 못했다: $work" }
    seedRepository(work)
    org.eclipse.jgit.api.Git.open(work).use { git ->
        File(work, "$message.txt").writeText("$message\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor(FIXED_IDENT).setCommitter(FIXED_IDENT).call()
    }
    return work
}
