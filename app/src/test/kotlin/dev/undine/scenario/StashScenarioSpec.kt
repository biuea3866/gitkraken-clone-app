package dev.undine.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val TRACKED = "base.txt"

/**
 * 시나리오 6 — 변경 → stash → 워킹트리 정리 → pop → 복원.
 *
 * stash 는 "잠시 치워 두는" 동작이라 **치운 뒤와 되돌린 뒤 둘 다** 확인해야 의미가 있다. 하나만 보면
 * 치우기만 되고 복원이 깨진 상태를 통과시킨다.
 */
class StashScenarioSpec : FunSpec({

    test("변경을 치우면 워킹트리가 깨끗해지고 되돌리면 내용이 그대로 돌아온다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.writeFile(TRACKED, "고친 내용\n")
        app.loadStatus.execute().isClean shouldBe false

        app.worktreeOps.stashPush(includeUntracked = false)

        app.loadStatus.execute().isClean shouldBe true
        app.readFile(TRACKED) shouldBe "base\n"
        app.worktreeOps.stashList() shouldHaveSize 1

        app.worktreeOps.stashPop()

        app.readFile(TRACKED) shouldBe "고친 내용\n"
        app.loadStatus.execute().isClean shouldBe false
        // pop 은 목록에서 그 항목을 지운다 — 남으면 같은 변경을 두 번 적용할 수 있다.
        app.worktreeOps.stashList().shouldBeEmpty()
    }

    test("추적되지 않는 파일은 요청할 때만 함께 치운다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.writeFile(TRACKED, "고친 내용\n")
        app.writeFile("scratch.txt", "임시\n")

        app.worktreeOps.stashPush(includeUntracked = false)

        // 추적되는 변경만 치웠으므로 임시 파일은 그대로 남는다.
        app.loadStatus.execute().untracked shouldBe listOf("scratch.txt")

        app.worktreeOps.stashPop()
        app.worktreeOps.stashPush(includeUntracked = true)

        app.loadStatus.execute().isClean shouldBe true
    }
})
