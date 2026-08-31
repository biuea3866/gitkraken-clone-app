package dev.undine.di

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.undo.GitOperationKind
import dev.undine.infrastructure.git.submodule.repositoryWithUninitializedSubmodule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import java.io.File

private const val SUBMODULE = "lib"
private const val MOVABLE_BRANCH = "movable"
private const val WORKTREE_BRANCH = "wt"

/**
 * 조립(`AppComponent`)이 실제 저장소 위에서 성립하는지 본다. **Mock 을 쓰지 않는다** — 대역으로
 * 바꾸면 조립이 아니라 대역을 검증하게 된다 (`testing` 규칙 1).
 *
 * 핵심 검증은 **기록기가 하나뿐인가** 다. `OperationRecorder` 를 받는 네 경로(graphops · reflog ·
 * submodule · worktree)를 실제로 실행해, 넷의 기록이 같은 이력 한 곳에 쌓이는지 확인한다.
 * 조립이 둘로 갈리면 이력도 조용히 둘로 갈라지고, 사용자는 되돌리려는 순간에야 그것을 안다.
 */
class AppComponentWiringSpec : BehaviorSpec({

    given("서브모듈이 딸린 저장소를 연 앱 조립") {

        `when`("recorder 를 받는 네 경로를 모두 실행하면") {
            then("네 기록이 같은 Undo 이력 한 곳에 쌓인다") {
                val work = repositoryWithUninitializedSubmodule()
                Git.open(work).use { git ->
                    git.branchCreate().setName(MOVABLE_BRANCH).call()
                    git.branchCreate().setName(WORKTREE_BRANCH).call()
                }
                val commits = Git.open(work).use { git -> git.log().call().map { CommitId.of(it.name) } }
                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val undo = component.newUndoScope()

                undo.submoduleActions.initialize.execute(SUBMODULE)
                undo.worktreeActions.add.execute(
                    path = RepositoryPath(File(tempdir(), WORKTREE_BRANCH).path),
                    branch = RefName(WORKTREE_BRANCH),
                )
                val graphOutcome = undo.executeGraphOperation.execute(
                    GraphOperation.ResetBranch(RefName(MOVABLE_BRANCH), commits.last()),
                )
                val recovery = undo.recoveryActions.recover(
                    commit = commits.first(),
                    target = RecoveryTarget.NewBranch(RefName("recovered")),
                )

                (graphOutcome as GraphOperationOutcome.Completed).undoRecordFailure.shouldBeNull()
                recovery.undoRecordFailure.shouldBeNull()
                undo.loadUndoHistory.execute().map { it.operation } shouldContainExactlyInAnyOrder listOf(
                    GitOperationKind.SUBMODULE_INIT,
                    GitOperationKind.WORKTREE_ADD,
                    GitOperationKind.BRANCH_MOVE,
                    GitOperationKind.REFLOG_RESTORE,
                )
            }
        }

        `when`("저장소를 바꿔 되돌리기 범위를 새로 만들면") {
            then("이전 범위의 이력이 따라오지 않는다") {
                val work = repositoryWithUninitializedSubmodule()
                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val before = component.newUndoScope()
                before.submoduleActions.initialize.execute(SUBMODULE)

                val after = component.newUndoScope()

                before.loadUndoHistory.execute() shouldHaveSize 1
                after.loadUndoHistory.execute() shouldHaveSize 0
                after.peekUndoTarget.execute()::class.simpleName shouldBe "None"
            }
        }

        `when`("아무 변경도 하지 않았으면") {
            then("Undo 이력이 비어 있고 되돌릴 대상도 없다") {
                val work = repositoryWithUninitializedSubmodule()
                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val undo = component.newUndoScope()

                undo.loadUndoHistory.execute() shouldHaveSize 0
                undo.peekUndoTarget.execute()::class.simpleName shouldBe "None"
            }
        }
    }

})

/** 설정 파일은 저장소 밖 임시 디렉터리에 둔다 — 워킹트리 안에 두면 상태 검증이 흔들린다. */
private fun componentIn(directory: File): AppComponent =
    AppComponent(File(directory, "settings.json").toPath())
