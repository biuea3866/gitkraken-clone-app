package dev.undine.scenario2

import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.reflog.RecoveryTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git

private const val LOST = "lost.txt"
private const val RECOVERED_BRANCH = "recovered"
private const val REFLOG_LIMIT = 50

/**
 * 2차 시나리오 2 — 커밋 → 잘못된 hard reset → reflog 로 복구 → 커밋 복원 확인.
 *
 * hard reset 도 앱 경로로 한다 (`ExecuteGraphOperationUseCase` 의 `ResetBranch`) — 셋업이 JGit 으로
 * 되감아 버리면 "앱이 만든 유실" 을 앱이 되찾는지 확인하지 못한다.
 */
class ReflogRecoveryScenario2Spec : FunSpec({

    test("hard reset 으로 잃은 커밋을 reflog 지점에서 새 브랜치로 되살린다") {
        openedApp { app ->
            val beforeCommit = app.head()

            app.writeFile(LOST, "잃어버릴 변경\n")
            val lost = app.stageAndCommit("잃어버릴 커밋", LOST)

            // 사용자의 실수: 커밋 직전 지점으로 브랜치를 hard reset 한다.
            // `GraphOperation` 의 브랜치는 `RefGateway.listBranches` 가 주는 **짧은 이름**이다.
            app.executeGraphOperation.execute(GraphOperation.ResetBranch(RefName(MAIN_BRANCH), to = beforeCommit))

            app.head() shouldBe beforeCommit
            app.messagesOldestFirst() shouldNotContain "잃어버릴 커밋"

            // reflog 는 그 커밋이 있었다는 유일한 단서다.
            val reflog = app.recoveryActions.loadReflog(REFLOG_LIMIT)
            reflog.entries.map { it.to } shouldContain lost

            val preview = app.recoveryActions.loadPreview(lost)
            preview.commit.message.trim() shouldBe "잃어버릴 커밋"
            preview.changedFiles.map { it.path } shouldContain LOST

            // 기본 복구는 **새 브랜치**다 — 기존 ref 를 옮기면 그 ref 가 가리키던 커밋을 또 잃는다.
            val outcome = app.recoveryActions.recover(lost, RecoveryTarget.NewBranch(RefName(RECOVERED_BRANCH)))

            outcome.undoRecordFailure shouldBe null
            outcome.value shouldBe RefName(RECOVERED_BRANCH)

            // 되살린 브랜치가 그 커밋을 가리키고 이력이 이어진다.
            Git.open(app.work).use { git ->
                git.repository.resolve(RECOVERED_BRANCH).name shouldBe lost.value
            }
            app.loadHistory
                .execute(listOf(RefName("refs/heads/$RECOVERED_BRANCH")), offset = 0, limit = 10)
                .map { it.message.trim() } shouldContainExactly listOf("잃어버릴 커밋", "initial")

            // 복구는 현재 브랜치를 움직이지 않는다 — 되찾으러 온 사용자가 새로 잃는 일이 없어야 한다.
            app.head() shouldBe beforeCommit
        }
    }
})
