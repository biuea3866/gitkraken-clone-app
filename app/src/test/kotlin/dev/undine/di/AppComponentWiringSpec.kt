package dev.undine.di

import dev.undine.application.graphops.GraphOperationOutcome
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.diagnostics.LogDirectoryLocation
import dev.undine.domain.gitconfig.EffectiveValue
import dev.undine.domain.gitconfig.GitConfigKey
import dev.undine.domain.gitconfig.GitConfigSource
import dev.undine.domain.graphops.GraphOperation
import dev.undine.domain.reflog.RecoveryTarget
import dev.undine.domain.typography.MonospaceFontListing
import dev.undine.domain.undo.GitOperationKind
import dev.undine.infrastructure.git.submodule.repositoryWithUninitializedSubmodule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * 핵심 검증은 **기록기가 하나뿐인가** 다. `OperationRecorder` 를 받는 경로를 실제로 실행해, 기록이
 * 같은 이력 한 곳에 쌓이는지 확인한다. 조립이 둘로 갈리면 이력도 조용히 둘로 갈라지고, 사용자는
 * 되돌리려는 순간에야 그것을 안다.
 *
 * UND-79 로 커밋·체크아웃·push·이어가기·계획 적용도 기록을 남기게 되어, 그 UseCase 들이 되돌리기
 * 범위로 옮겨 왔다 — 앱 수명에 두면 저장소를 바꾼 뒤에도 옛 이력에 기록한다 (결정 G29).
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

        `when`("범위로 옮겨 온 커밋·체크아웃 경로를 실행하면") {
            then("같은 범위의 이력에 함께 쌓인다") {
                val work = repositoryWithUninitializedSubmodule()
                Git.open(work).use { git ->
                    git.branchCreate().setName(MOVABLE_BRANCH).call()
                    git.repository.config.apply {
                        setString("user", null, "name", "Undine Test")
                        setString("user", null, "email", "test@undine.dev")
                        save()
                    }
                }
                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val undo = component.newUndoScope()

                File(work, "note.txt").writeText("메모\n")
                undo.stagingActions.stageFiles.execute(listOf("note.txt"))
                undo.stagingActions.commitStaged.execute("메모를 남긴다").undoRecordFailure.shouldBeNull()
                undo.checkoutBranch(RefName(MOVABLE_BRANCH)).undoRecordFailure.shouldBeNull()

                undo.loadUndoHistory.execute().map { it.operation } shouldContainExactlyInAnyOrder listOf(
                    GitOperationKind.COMMIT,
                    GitOperationKind.CHECKOUT,
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

        `when`("환경설정이 소비하는 네 계약을 조립에서 꺼내 실행하면") {
            then("git 실효값·서체 목록·로그 위치가 실제로 답하고 신원 묶음이 원자적 수정을 담는다") {
                val work = repositoryWithUninitializedSubmodule()
                Git.open(work).use { git ->
                    git.repository.config.apply {
                        setString("init", null, "defaultBranch", "trunk")
                        save()
                    }
                }
                val directory = tempdir()
                val component = componentIn(directory)

                // 저장소가 열려 있지 않아도 실효값을 말할 수 있어야 한다 — 조회 전에 먼저 부른다.
                component.readEffectiveConfig.execute(null)

                val repositoryScoped =
                    component.readEffectiveConfig.execute(RepositoryPath(work.path))
                val fonts = component.loadMonospaceFonts.execute()
                val logDirectory = component.diagnosticsUseCases.locateLogDirectory.execute()

                repositoryScoped[GitConfigKey.INIT_DEFAULT_BRANCH] shouldBe
                    EffectiveValue("trunk", GitConfigSource.REPOSITORY)
                // 열거 결과는 기계마다 다르므로 **계약이 약속한 성질**만 본다 (오름차순·중복 없음).
                when (fonts) {
                    is MonospaceFontListing.Available ->
                        fonts.families shouldBe fonts.families.distinct().sorted()

                    is MonospaceFontListing.Unavailable -> fonts.cause.shouldNotBeNull()
                }
                // appDirectory 를 넘겼으므로 그 디렉터리가 그대로 로그 위치가 된다.
                logDirectory shouldBe LogDirectoryLocation.Found(directory.toPath())
                component.identityUseCases.updateProfile.shouldNotBeNull()
                component.identityUseCases.profileUsage.shouldNotBeNull()
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

/** 설정 파일과 로그는 저장소 밖 임시 디렉터리에 둔다 — 워킹트리 안에 두면 상태 검증이 흔들린다. */
private fun componentIn(directory: File): AppComponent = AppComponent(
    settingsFile = File(directory, "settings.json").toPath(),
    appDirectory = directory.toPath(),
)
