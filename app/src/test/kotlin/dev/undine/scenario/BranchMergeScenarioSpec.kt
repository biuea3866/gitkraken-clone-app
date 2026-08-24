package dev.undine.scenario

import dev.undine.domain.RefName
import dev.undine.domain.merge.MergeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git

private const val FEATURE = "feature"
private const val FEATURE_FILE = "feature.txt"

/**
 * 시나리오 2 — 브랜치 생성 → 체크아웃 → 커밋 → 복귀 → 병합.
 *
 * 병합 커밋의 **부모가 2개**인지까지 본다. 빨리 감기로 처리되면 부모가 1개이고 이력이 갈라졌던 사실이
 * 사라지므로, 병합 커밋을 강제한 경로와 빨리 감기 경로를 따로 검증한다.
 */
class BranchMergeScenarioSpec : FunSpec({

    test("분기해 커밋한 뒤 병합 커밋을 만들면 부모가 2개다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        val head = app.open()

        // 분기 지점을 만들고 양쪽에 서로 다른 커밋을 쌓는다.
        app.refs.createBranch(RefName("refs/heads/$FEATURE"), headCommitId(app.work))
        app.refs.checkout(RefName("refs/heads/$FEATURE"), force = false)
        app.writeFile(FEATURE_FILE, "기능\n")
        app.stageAndCommit("기능을 추가한다", FEATURE_FILE)

        app.refs.checkout(mainRef(), force = false)
        app.writeFile("main.txt", "본선\n")
        app.stageAndCommit("본선을 고친다", "main.txt")

        val result = app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = false)

        result.shouldBeInstanceOf<MergeResult.Succeeded>().fastForward shouldBe false
        parentsOfHead(app) shouldBe 2
        head.currentBranch shouldBe mainRef()
        // 양쪽 변경이 결과 워킹트리에 함께 있다.
        app.readFile(FEATURE_FILE) shouldBe "기능\n"
        app.readFile("main.txt") shouldBe "본선\n"
    }

    test("본선이 앞서지 않으면 빨리 감기로 병합되고 부모가 1개다") {
        val app = ScenarioApp(seedRepository(tempdir()))
        app.open()

        app.refs.createBranch(RefName("refs/heads/$FEATURE"), headCommitId(app.work))
        app.refs.checkout(RefName("refs/heads/$FEATURE"), force = false)
        app.writeFile(FEATURE_FILE, "기능\n")
        app.stageAndCommit("기능만 추가한다", FEATURE_FILE)
        app.refs.checkout(mainRef(), force = false)

        val result = app.mergeBranch.execute(RefName("refs/heads/$FEATURE"), allowFastForward = true)

        result.shouldBeInstanceOf<MergeResult.Succeeded>().fastForward shouldBe true
        parentsOfHead(app) shouldBe 1
        app.messagesOldestFirst() shouldContain "기능만 추가한다"
    }
})

private fun parentsOfHead(app: ScenarioApp): Int =
    Git.open(app.work).use { git ->
        git.log().setMaxCount(1).call().first().parentCount
    }
