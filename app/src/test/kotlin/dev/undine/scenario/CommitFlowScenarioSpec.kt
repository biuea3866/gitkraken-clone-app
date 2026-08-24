package dev.undine.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.UserConfig

private const val NOTE = "note.md"

/**
 * 시나리오 1 — 저장소 열기 → 파일 추가 → stage → 커밋 → 이력 반영.
 *
 * 개별 티켓의 테스트가 모두 통과해도 **이어 붙였을 때** 깨지는 것이 있다. 여기서는 실제 임시 저장소
 * 하나를 열고 앱 UseCase 만으로 커밋까지 간다.
 */
class CommitFlowScenarioSpec : FunSpec({

    test("시나리오 저장소는 주변 환경과 무관하게 작성자가 설정돼 있다") {
        // 앱의 커밋 경로는 작성자가 없으면 커밋하지 않는다. 전역 설정에 기대면 개발자 머신에서만
        // 통과하고 CI 에서 깨진다 — 저장소 로컬 설정이 그 의존을 끊는다.
        val work = seedRepository(tempdir())

        Git.open(work).use { git ->
            val user = git.repository.config.get(UserConfig.KEY)
            user.isAuthorNameImplicit shouldBe false
            user.isAuthorEmailImplicit shouldBe false
            user.authorName shouldBe FIXED_IDENT.name
        }
    }

    test("파일을 추가해 커밋하면 이력과 워킹트리 상태가 함께 맞는다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.writeFile(NOTE, "첫 메모\n")
        // 커밋 전에는 추적되지 않는 파일로 보인다.
        app.loadStatus.execute().untracked shouldContainExactly listOf(NOTE)

        app.stageAndCommit("메모를 추가한다", NOTE)

        app.messagesOldestFirst() shouldContainExactly listOf("initial", "메모를 추가한다")
        val status = app.loadStatus.execute()
        // 커밋 뒤 워킹트리는 깨끗하다 — staged 가 남아 있으면 다음 커밋에 딸려 들어간다.
        status.staged.shouldBeEmpty()
        status.unstaged.shouldBeEmpty()
        status.untracked.shouldBeEmpty()
    }

    test("커밋한 내용이 이력 조회에 그 순서로 나온다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.writeFile(NOTE, "하나\n")
        app.stageAndCommit("하나", NOTE)
        app.writeFile(NOTE, "둘\n")
        app.stageAndCommit("둘", NOTE)

        // 이력 조회는 최신부터 준다 — 화면이 그 순서로 그린다.
        val commits = app.loadHistory.execute(listOf(mainRef()), offset = 0, limit = 10)
        commits.map { it.message.trim() } shouldContainExactly listOf("둘", "하나", "initial")
    }

    test("스테이징하지 않은 변경은 커밋에 들어가지 않는다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.writeFile(NOTE, "올릴 것\n")
        app.writeFile("other.txt", "안 올릴 것\n")
        app.stageAndCommit("하나만 올린다", NOTE)

        app.loadStatus.execute().untracked shouldContainExactly listOf("other.txt")
        app.messagesOldestFirst().last() shouldBe "하나만 올린다"
    }
})
