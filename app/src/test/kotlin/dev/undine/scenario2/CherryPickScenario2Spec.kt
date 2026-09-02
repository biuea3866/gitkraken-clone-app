package dev.undine.scenario2

import dev.undine.domain.RefName
import dev.undine.domain.cherrypick.CherryPickResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git

private const val TOPIC = "topic"
private const val PICKED = "picked.txt"

/**
 * 2차 시나리오 1 — 브랜치 A 의 커밋을 브랜치 B 로 cherry-pick 하고 이력 반영을 확인한다.
 *
 * 앱 경로만 쓴다: 체크아웃은 `CheckoutBranchUseCase`, 적용은 `CherryPickCommitsUseCase`.
 * 브랜치 생성만 셋업이 JGit 으로 한다 — 어느 화면도 그 경로를 노출하지 않는다.
 */
class CherryPickScenario2Spec : FunSpec({

    test("브랜치 A 의 커밋을 cherry-pick 하면 브랜치 B 이력에 그 변경이 들어온다") {
        openedApp { app ->
            Git.open(app.work).use { git -> git.branchCreate().setName(TOPIC).call() }

            // 브랜치 A(main)에서만 커밋한다.
            app.writeFile(PICKED, "가져갈 변경\n")
            val source = app.stageAndCommit("가져갈 커밋", PICKED)
            val mainHistory = app.messagesOldestFirst()

            // 브랜치 B(topic)로 옮겨 그 커밋만 가져온다.
            app.checkoutBranch(RefName(TOPIC))
            val outcome = app.cherryPickCommits.execute(listOf(source))

            val applied = outcome.result.shouldBeInstanceOf<CherryPickResult.Applied>()
            applied.created.size shouldBe 1
            outcome.undoRecordFailure shouldBe null

            // 대상 브랜치 이력에 반영됐고 워킹트리에도 그 파일이 있다.
            app.messagesOldestFirst() shouldContainExactly listOf("initial", "가져갈 커밋")
            app.readFile(PICKED) shouldBe "가져갈 변경\n"
            // 적용 결과가 지목한 커밋이 실제 HEAD 다.
            applied.created.single() shouldBe app.head()

            // 원본 브랜치는 그대로다.
            app.checkoutBranch(mainRef())
            app.messagesOldestFirst() shouldContainExactly mainHistory
        }
    }
})
