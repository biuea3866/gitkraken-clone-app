package dev.undine.scenario2

import dev.undine.domain.CommitId
import dev.undine.domain.bisect.BisectResult
import dev.undine.domain.bisect.BisectVerdict
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git

private const val CODE = "code.txt"
private const val BUG = "심어 둔 버그"
private const val HEALTHY = "정상 동작"

/** 버그를 심은 커밋의 순번(1부터). 앞뒤로 정상 커밋이 남아야 이분 탐색이 실제로 좁힌다. */
private const val BUG_AT = 4
private const val COMMIT_COUNT = 7

/** 좁혀지지 않는 탐색이 무한 루프가 되지 않게 두는 상한. 커밋 수보다 크면 판정이 바뀌지 않는다. */
private const val MAX_STEPS = 16

/**
 * 2차 시나리오 3 — 버그 커밋을 심고 bisect 로 그 커밋을 확정한다.
 *
 * 판정은 **실제 워킹트리 내용**으로 한다. 체크아웃이 정말 일어나지 않으면 판정 근거가 바뀌지 않아
 * 탐색이 엉뚱한 커밋을 지목하므로, 이 시나리오가 그 배선까지 함께 확인한다.
 */
class BisectScenario2Spec : FunSpec({

    test("심어 둔 버그 커밋을 bisect 가 최초 나쁜 커밋으로 확정한다") {
        openedApp { app ->
            val commits = app.seedBuggyHistory()
            val startBranch = Git.open(app.work).use { it.repository.branch }

            var result = app.recoveryActions.startBisect(good = commits[0], bad = commits[COMMIT_COUNT - 1]).value
            var steps = 0
            while (result is BisectResult.Testing && steps < MAX_STEPS) {
                steps += 1
                // 체크아웃된 워킹트리를 읽어 판정한다 — 사용자가 실제로 하는 일과 같다.
                val verdict = if (app.readFile(CODE).contains(BUG)) BisectVerdict.BAD else BisectVerdict.GOOD
                result = app.recoveryActions.markBisect(verdict).value
            }

            result.shouldBeInstanceOf<BisectResult.FirstBad>().commit shouldBe commits[BUG_AT - 1]

            // 확정 뒤 reset 은 시작 지점으로 되돌린다 — 세션 상태도 남지 않는다.
            app.recoveryActions.resetBisect()
            app.recoveryActions.restoreBisect() shouldBe null
            Git.open(app.work).use { it.repository.branch } shouldBe startBranch.shouldNotBeNull()
            app.head() shouldBe commits[COMMIT_COUNT - 1]
        }
    }
})

/**
 * [BUG_AT] 번째 커밋에서 버그가 들어오고 그 뒤로 남아 있는 선형 이력.
 *
 * 커밋은 앱 경로(`stage` → `commit`)로 쌓는다 — 셋업이 JGit 으로 만들면 앱이 만든 이력에서
 * 탐색하는 것을 확인하지 못한다.
 */
private suspend fun Scenario2App.seedBuggyHistory(): List<CommitId> =
    (1..COMMIT_COUNT).map { index ->
        writeFile(CODE, if (index >= BUG_AT) "$BUG $index\n" else "$HEALTHY $index\n")
        stageAndCommit("커밋 $index", CODE)
    }
