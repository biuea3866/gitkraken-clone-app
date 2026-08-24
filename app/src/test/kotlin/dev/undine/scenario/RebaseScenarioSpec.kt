package dev.undine.scenario

import dev.undine.domain.RefName
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebasePlan
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File

private const val TOPIC = "topic"

/**
 * 시나리오 7 — 커밋 여러 개 → 대화형 리베이스(재정렬 + squash) → 결과 이력 확인.
 *
 * 계획을 세우는 동안 저장소가 그대로인지도 함께 본다 — 적용 전 무변경은 이 기능의 핵심 성질이라
 * 단위 테스트뿐 아니라 실제 저장소에서도 성립해야 한다.
 */
class RebaseScenarioSpec : FunSpec({

    test("재정렬한 계획을 적용하면 결과 이력이 계획 순서와 같다") {
        val app = topicApp(tempdir(), commits = listOf("첫", "둘", "셋"))
        val before = app.messagesOldestFirst()

        val targets = app.loadRebaseTargets.execute(mainRef())
        targets.map { it.commit.message.trim() } shouldContainExactly listOf("첫", "둘", "셋")

        // 계획만 세운 동안에는 이력이 그대로다.
        val plan = RebasePlan.of(targets).move(from = 2, to = 0)
        app.messagesOldestFirst() shouldContainExactly before

        app.applyRebasePlan.execute(mainRef(), plan) shouldBe InteractiveRebaseOutcome.Completed

        app.messagesOldestFirst() shouldContainExactly listOf("initial", "셋", "첫", "둘")
    }

    test("squash 를 지정한 계획을 적용하면 이력이 줄고 파일은 모두 남는다") {
        val app = topicApp(tempdir(), commits = listOf("기반", "고침"))

        val plan = RebasePlan.of(app.loadRebaseTargets.execute(mainRef()))
            .withAction(1, RebaseAction.Squash)

        app.applyRebasePlan.execute(mainRef(), plan) shouldBe InteractiveRebaseOutcome.Completed

        // 메시지 본문은 squash 방식에 따라 달라 개수만 본다.
        app.messagesOldestFirst() shouldHaveSize 2
        // 합친 커밋에 두 변경이 모두 들어 있다.
        File(app.work, "기반.txt").isFile shouldBe true
        File(app.work, "고침.txt").isFile shouldBe true
    }

    test("계획을 적용하지 않고 버리면 저장소가 그대로다") {
        val app = topicApp(tempdir(), commits = listOf("첫", "둘"))
        val before = app.messagesOldestFirst()
        val head = headOf(app.work)

        // 재정렬·동작 지정을 마음껏 해도 적용하지 않으면 아무 일도 없다.
        RebasePlan.of(app.loadRebaseTargets.execute(mainRef()))
            .move(from = 1, to = 0)
            .withAction(1, RebaseAction.Drop)

        app.messagesOldestFirst() shouldContainExactly before
        headOf(app.work) shouldBe head
    }
})

/** `main` 에서 갈라진 `topic` 에 커밋을 쌓고 그 위에 서 있는 앱. */
private suspend fun topicApp(work: File, commits: List<String>): ScenarioApp {
    seedRepository(work)
    val app = ScenarioApp(work)
    app.open()
    app.refs.createBranch(RefName("refs/heads/$TOPIC"), headCommitId(work))
    app.refs.checkout(RefName("refs/heads/$TOPIC"), force = false)
    commits.forEach { message ->
        app.writeFile("$message.txt", "$message\n")
        app.stageAndCommit(message, "$message.txt")
    }
    return app
}
