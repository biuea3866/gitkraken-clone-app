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
import dev.undine.infrastructure.git.submodule.commitFile
import dev.undine.infrastructure.git.submodule.repositoryWithSubmodule
import dev.undine.infrastructure.git.submodule.repositoryWithUninitializedSubmodule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File

private const val SUBMODULE = "lib"

/** 경쟁할 호출이 모두 임계 구역 대기열에 들어가기를 기다리는 시간. 통과가 아니라 **경쟁을 만들기** 위한 값이다. */
private const val SERIAL_BOUNDARY_QUEUE_MILLIS = 200L
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

        `when`("서브모듈 포인터를 부모에 반영하면") {
            then("gitlink 하나만 담긴 커밋이 되고 앱의 다른 스테이징은 커밋되지 않는다") {
                val work = repositoryWithSubmodule()
                Git.open(work).use { git -> git.configureTestAuthor() }
                // 서브모듈 HEAD 를 앞으로 옮겨 부모 gitlink 와 어긋나게 만든다.
                val child = File(work, SUBMODULE)
                Git.open(child).use { git -> git.commitFile(child, "child.txt", "다음\n", "child 진전") }
                // 앱의 다른 경로가 이미 올려 둔 변경. 이 커밋에 섞이면 사용자가 고르지 않은 이력이 된다.
                File(work, "note.txt").writeText("메모\n")
                Git.open(work).use { git -> git.add().addFilepattern("note.txt").call() }

                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val created = component.commitSubmodulePointer.execute(SUBMODULE, "서브모듈 포인터 갱신")

                Git.open(work).use { git ->
                    git.changedPathsOf(created.commitId.value) shouldBe setOf(SUBMODULE)
                    // 남의 변경은 인덱스에 남는다 — 커밋되지도, 되돌려지지도 않는다.
                    git.status().call().added shouldContainExactlyInAnyOrder listOf("note.txt")
                }
            }
        }

        `when`("서브모듈 포인터 반영이 임계 구역 대기 중 취소되면") {
            then("부모 HEAD 도 인덱스의 gitlink 도 전이 이전 그대로다") {
                val work = repositoryWithSubmodule()
                Git.open(work).use { git -> git.configureTestAuthor() }
                val child = File(work, SUBMODULE)
                Git.open(child).use { git -> git.commitFile(child, "child.txt", "다음\n", "child 진전") }

                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val headBefore = headOf(work)
                val gitlinkBefore = indexGitlinkOf(work)

                component.holdingGitBoundary(work) { _ ->
                    val committing = launch(Dispatchers.Default) {
                        component.commitSubmodulePointer.execute(SUBMODULE, "서브모듈 포인터 갱신")
                    }
                    delay(SERIAL_BOUNDARY_QUEUE_MILLIS)
                    committing.cancelAndJoin()
                }

                // 커밋이 만들어지지 않은 것만으로는 부족하다 — gitlink 만 인덱스에 올라간 중간 상태도 없어야
                // 다음 커밋이 사용자가 고르지 않은 변경을 담지 않는다.
                headOf(work) shouldBe headBefore
                indexGitlinkOf(work) shouldBe gitlinkBefore
            }
        }

        `when`("서브모듈 포인터 반영과 다른 stage 가 같은 경계에서 경쟁하면") {
            then("gitlink 만 커밋되고 경쟁한 변경은 인덱스에 남는다") {
                val work = repositoryWithSubmodule()
                Git.open(work).use { git -> git.configureTestAuthor() }
                val child = File(work, SUBMODULE)
                Git.open(child).use { git -> git.commitFile(child, "child.txt", "다음\n", "child 진전") }
                File(work, "note.txt").writeText("메모\n")

                val component = componentIn(tempdir())
                component.welcomeActions.openRepository.execute(RepositoryPath(work.path))
                val staging = component.newUndoScope().stagingActions

                // 두 호출이 모두 대기열에 들어가도록 경계를 붙잡아 둔다 — 어느 쪽이 먼저 통과하든
                // gitlink 반영의 stage 와 commit 사이에는 끼어들 수 없어야 한다.
                val created = component.holdingGitBoundary(work) { releaseBoundary ->
                    val committing = async(Dispatchers.Default) {
                        component.commitSubmodulePointer.execute(SUBMODULE, "서브모듈 포인터 갱신")
                    }
                    val competing = launch(Dispatchers.Default) { staging.stageFiles.execute(listOf("note.txt")) }
                    // 둘 다 대기열에 들어간 뒤에 연다 — 먼저 열면 경쟁이 아니라 순차 실행이 된다.
                    delay(SERIAL_BOUNDARY_QUEUE_MILLIS)
                    releaseBoundary()
                    competing.join()
                    committing.await()
                }

                Git.open(work).use { git ->
                    git.changedPathsOf(created.commitId.value) shouldBe setOf(SUBMODULE)
                    git.status().call().added shouldContainExactlyInAnyOrder listOf("note.txt")
                }
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

/**
 * Git 임계 구역을 붙잡은 채 [block] 을 돌린다.
 *
 * 세션 전이의 반영 훅은 **구역 안에서** 불리므로(`RepositorySessionUseCase`), 그 훅을 관문으로 잡으면
 * 실제 앱이 쓰는 경계를 그대로 잠글 수 있다. 대역을 끼우지 않고 조립된 컴포넌트로 경쟁을 재현하는
 * 유일한 통로다.
 */
private suspend fun <T> AppComponent.holdingGitBoundary(
    work: File,
    block: suspend CoroutineScope.(releaseBoundary: () -> Unit) -> T,
): T {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    return coroutineScope {
        val holder = launch(Dispatchers.Default) {
            repositorySession.open(RepositoryPath(work.path)) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            // 경계 안에서 끝나는 결과를 기다리려면 [block] 이 **스스로** 열어야 한다 — 여기서만 열면
            // 경계를 기다리는 호출의 결과를 기다리는 순간 서로를 기다린다.
            block { release.complete(Unit) }
        } finally {
            release.complete(Unit)
            holder.join()
        }
    }
}

private fun Git.configureTestAuthor() {
    repository.config.apply {
        setString("user", null, "name", "Undine Test")
        setString("user", null, "email", "test@undine.dev")
        save()
    }
}

private fun headOf(work: File): String =
    Git.open(work).use { git -> requireNotNull(git.repository.resolve("HEAD")).name }

/** 인덱스에 기록된 gitlink. 커밋 없이 여기만 바뀐 상태가 곧 "부분 상태" 다. */
private fun indexGitlinkOf(work: File): String? =
    Git.open(work).use { git -> git.repository.readDirCache().getEntry(SUBMODULE)?.objectId?.name }

/** 커밋이 부모 대비 실제로 담은 경로. "의도한 변경만 커밋했는가" 는 인덱스가 아니라 트리가 답한다. */
private fun Git.changedPathsOf(commit: String): Set<String> {
    val created = repository.parseCommit(repository.resolve(commit))
    val parent = repository.parseCommit(created.getParent(0).id)
    return DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
        formatter.setRepository(repository)
        formatter.scan(parent.tree, created.tree).map { entry -> entry.newPath }.toSet()
    }
}
