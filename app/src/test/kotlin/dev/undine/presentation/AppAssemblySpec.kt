package dev.undine.presentation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.di.AppComponent
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryPath
import dev.undine.domain.graphops.GraphOperation
import dev.undine.infrastructure.git.submodule.cloneOf
import dev.undine.infrastructure.git.submodule.seedRepository
import dev.undine.presentation.graph.GraphTags
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.systemStrings
import dev.undine.presentation.i18n.tabs
import dev.undine.presentation.palette.CommandId
import dev.undine.presentation.palette.CommandOutcome
import dev.undine.presentation.palette.PaletteTags
import dev.undine.presentation.palette.Shortcut
import dev.undine.presentation.palette.ShortcutModifier
import dev.undine.presentation.palette.execute
import dev.undine.presentation.palette.toBinding
import dev.undine.presentation.welcome.WelcomeTags
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 실제 저장소를 열고 그래프까지 읽는 경로라 기본 1초로는 모자란다. */
private const val WAIT_MILLIS = 30_000L

private val REFRESH_COMMAND = CommandId("refs.refresh")
private val CHERRY_PICK_COMMAND = CommandId("graph.cherryPick")
private val OPEN_PALETTE_COMMAND = CommandId("palette.open")
private val CLOSE_REPOSITORY_COMMAND = CommandId("repository.close")

/** 저장해 둘 단축키. 기존 기본 단축키와 겹치지 않게 수식키 둘을 함께 쓴다. */
private val OVERRIDE_SHORTCUT = Shortcut(Key.F8, setOf(ShortcutModifier.PRIMARY, ShortcutModifier.ALT))

/** clone 사이에서 baseline 이 같아도 이력이 섞이지 않는지 보려고 옮겨 볼 브랜치. */
private const val MOVABLE_BRANCH = "movable"

/** 저장소를 구분하는 표식. 컨텍스트가 어느 저장소의 ref 를 들고 있는지는 이 이름으로만 말할 수 있다. */
private const val FIRST_ONLY_BRANCH = "only-in-first"
private const val SECOND_ONLY_BRANCH = "only-in-second"

/**
 * 조립된 앱을 **그대로 띄워** 배선이 성립하는지 본다.
 *
 * 목적지 분기(`destinationFor`)나 등록 함수를 따로 부르는 테스트는 그 함수가 맞다는 것만 말한다 —
 * 앱이 그 함수를 부르는지, 부른 결과를 화면으로 옮기는지는 말하지 않는다. 여기서는 `AppRoot` 를
 * 렌더해 **일곱 화면 분기 · 탭 막대 배선 · 그래프 홀더 공유 · 시작 시 단축키 적용 · 탭 전환과
 * 닫기·재열기에서의 Undo 범위 수명**을 한 조립 위에서 확인한다.
 *
 * **Mock 을 쓰지 않는다** — 실제 임시 저장소와 실제 `AppComponent` 로 돈다 (`testing` 규칙 1).
 */
@OptIn(ExperimentalTestApi::class)
class AppAssemblySpec : FunSpec({

    test("배선된 일곱 화면이 조립 경로 그대로 그려지고 저장된 단축키가 시작 시 얹힌다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("첫.txt")
        rememberAsRecent(settingsFile, work)
        AppComponent(settingsFile, settingsFile.parent).updatePreferences.execute { stored ->
            stored.copy(shortcutOverrides = mapOf(REFRESH_COMMAND.value to OVERRIDE_SHORTCUT.toBinding()))
        }

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)

            // 저장된 오버라이드를 시작 시 얹었다 — 설정 파일에서 읽어 실효 단축키가 된다.
            wiring.registry.commandFor(OVERRIDE_SHORTCUT)?.id shouldBe REFRESH_COMMAND

            // 일곱 화면 전부가 같은 조립 위에서 그려진다. 하나라도 배선이 비면 여기서 터진다.
            AppDestination.entries.forEach { destination ->
                wiring.navigation.go(destination)
                waitForIdle()
                onNodeWithTag(AppDestinationTags.of(destination)).assertExists()
            }
        }
    }

    test("팔레트의 그래프 조작 명령이 그래프 화면이 받은 그 홀더에 확인창을 연다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("그래프.txt")
        rememberAsRecent(settingsFile, work)
        val head = headCommitOf(work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithTag(GraphTags.row(head)).fetchSemanticsNodes().isNotEmpty()
            }

            // 선택이 있어야 그래프 명령이 열린다 — 그 선택도 그래프 화면이 셸에 올린 것이다.
            onNodeWithTag(GraphTags.row(head)).performClick()
            waitForIdle()
            wiring.registry.commands.single { it.id == CHERRY_PICK_COMMAND }.execute()
                .shouldBeInstanceOf<CommandOutcome.Executed>()
            waitForIdle()

            wiring.dragDrop.confirmation.shouldNotBeNull().choices shouldBe
                listOf(GraphOperation.CherryPick(head, BranchTarget.Current))
            // 확인창은 **그래프 화면이 받은 홀더**가 그린다 — 다른 홀더를 넘겼다면 열리지 않는다.
            onNodeWithText(systemStrings().common.ok).assertExists()
        }
    }

    test("팔레트는 열기 전에는 그려지지 않고 명령으로 열면 그려진다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("팔레트.txt")
        rememberAsRecent(settingsFile, work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)

            // 닫힌 팔레트를 그리면 명령 목록이 화면을 덮은 채 남고 그 아래 클릭까지 가로챈다.
            onNodeWithTag(PaletteTags.ROOT).assertDoesNotExist()

            wiring.registry.commands.single { it.id == OPEN_PALETTE_COMMAND }.execute()
            waitForIdle()

            onNodeWithTag(PaletteTags.ROOT).assertExists()
        }
    }

    // baseline(브랜치+HEAD)이 같은 clone 둘이라, 범위를 갈아 끼우지 않으면 A 의 되돌리기가 B 에서
    // 실행된다 — `HardResetTo` 라면 B 의 작업 트리를 잃는다 (결정 G29).
    test("저장소를 바꾸면 이전 저장소의 Undo 이력이 따라오지 않는다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val first = seedRepository("전환.txt")
        Git.open(first).use { opened -> opened.branchCreate().setName(MOVABLE_BRANCH).call() }
        val second = cloneOf(first)
        rememberAsRecent(settingsFile, first, second)
        val head = headCommitOf(first)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, first)
            val recorded = wiring.currentUndo()
            runBlocking {
                recorded.scope.executeGraphOperation.execute(
                    GraphOperation.ResetBranch(RefName(MOVABLE_BRANCH), head),
                )
                recorded.scope.loadUndoHistory.execute()
            } shouldHaveSize 1

            wiring.navigation.go(AppDestination.WELCOME)
            waitForIdle()
            openRecent(wiring, second)
            waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.currentUndo() !== recorded }

            // 바뀐 저장소에는 되돌릴 것이 없다 — 이전 저장소의 기록은 이 범위에 없다.
            val switched = wiring.currentUndo()
            runBlocking { switched.scope.loadUndoHistory.execute() }.shouldBeEmpty()
            runBlocking { switched.scope.peekUndoTarget.execute() }::class.simpleName shouldBe "None"
            // 두 저장소가 탭으로 남는다 — 전환은 앞 탭을 닫는 것이 아니다 (UND-81).
            onAllNodesWithText(systemStrings().tabs.closeTab).fetchSemanticsNodes() shouldHaveSize 2

            // 앞 탭이 열려 있으므로 그 저장소의 이력은 살아 있다 — 범위의 수명은 탭이다 (UND-81).
            // 되돌아가는 경로가 전환이든 같은 경로를 새 탭으로 여는 것이든 같은 범위를 본다.
            wiring.navigation.go(AppDestination.WELCOME)
            waitForIdle()
            openRecent(wiring, first)
            waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.currentUndo() !== switched }
            runBlocking { wiring.currentUndo().scope.loadUndoHistory.execute() } shouldHaveSize 1
            // clone 은 여전히 갈려 있다 — baseline 이 같아도 세션 키가 다르기 때문이다 (결정 G29).
            runBlocking { switched.scope.loadUndoHistory.execute() }.shouldBeEmpty()
        }
    }

    // 닫기는 전환과 다른 경로다 — 범위 키가 `null` 을 거쳐 같은 값으로 돌아온다. 재열기에서 닫기 전
    // 범위를 이어 쓰면 버렸어야 할 이력이 되살아난다 (결정 G29: 스택은 세션보다 짧게 산다).
    test("저장소를 닫았다 같은 저장소를 다시 열어도 닫기 전 Undo 이력이 되살아나지 않는다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("닫기.txt")
        Git.open(work).use { opened -> opened.branchCreate().setName(MOVABLE_BRANCH).call() }
        rememberAsRecent(settingsFile, work)
        val head = headCommitOf(work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)
            val recorded = wiring.currentUndo()
            runBlocking {
                recorded.scope.executeGraphOperation.execute(
                    GraphOperation.ResetBranch(RefName(MOVABLE_BRANCH), head),
                )
                recorded.scope.loadUndoHistory.execute()
            } shouldHaveSize 1

            // 사용자가 하는 그대로 — 등록된 닫기 명령으로 닫는다.
            wiring.registry.commands.single { it.id == CLOSE_REPOSITORY_COMMAND }.execute()
                .shouldBeInstanceOf<CommandOutcome.Executed>()
            waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.currentUndo() !== recorded }
            val closed = wiring.currentUndo()

            // 선택이 실제로 `null` 로 갔다 — 저장소가 필요한 화면은 더 이상 그려지지 않는다.
            onNodeWithTag(AppDestinationTags.of(AppDestination.REPOSITORY)).assertDoesNotExist()
            onNodeWithTag(AppDestinationTags.of(AppDestination.WELCOME)).assertExists()
            runBlocking { closed.scope.loadUndoHistory.execute() }.shouldBeEmpty()

            openRecent(wiring, work)
            waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.currentUndo() !== closed }

            // 같은 저장소지만 닫힌 세션의 이력은 없다 — 되돌릴 대상도 없다.
            val reopened = wiring.currentUndo()
            runBlocking { reopened.scope.loadUndoHistory.execute() }.shouldBeEmpty()
            runBlocking { reopened.scope.peekUndoTarget.execute() }::class.simpleName shouldBe "None"
        }
    }

    // 탭·셸 선택은 임계 구역 안에서 옮겨 가는데 컨텍스트만 그 밖에서 채워지면, 그 사이 화면은
    // **이미 지나간 저장소의 ref** 를 보여 준다 — 그 창에 누른 조작이 다른 저장소로 간다 (결정 G42).
    test("탭을 바꾸면 화면 컨텍스트가 이전 저장소의 ref 를 들고 있는 창이 없다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val first = seedRepository("첫.txt")
        val second = seedRepository("둘.txt")
        Git.open(first).use { opened -> opened.branchCreate().setName(FIRST_ONLY_BRANCH).call() }
        Git.open(second).use { opened -> opened.branchCreate().setName(SECOND_ONLY_BRANCH).call() }
        rememberAsRecent(settingsFile, first, second)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, first)
            // 앞 저장소의 조회가 끝난 뒤에 바꾼다 — 채워지기 전에 바꾸면 무엇을 보고 있었는지 말할 수 없다.
            waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.holdsBranch(FIRST_ONLY_BRANCH) }
            val firstUndo = wiring.currentUndo()

            wiring.navigation.go(AppDestination.WELCOME)
            waitForIdle()

            // **표집은 별도 스레드에서 한다.** `waitUntil` 은 매 확인마다 조합이 한가해지기를 기다리므로
            // (효과의 조회까지 끝난 뒤에야 본다) 전이와 조회 **사이**의 상태를 영영 보지 못한다.
            // 결함은 바로 그 사이에만 보이므로, 한가해짐을 기다리지 않는 눈이 따로 있어야 한다.
            val stopSampling = AtomicBoolean(false)
            val sawPreviousRepository = AtomicBoolean(false)
            val sampler = thread(name = "context-sampler") {
                while (!stopSampling.get()) {
                    // 범위가 이미 바뀌었는데 컨텍스트가 앞 저장소의 ref 를 들고 있는 순간 = 결함.
                    if (wiring.currentUndo() !== firstUndo && wiring.holdsBranch(FIRST_ONLY_BRANCH)) {
                        sawPreviousRepository.set(true)
                    }
                    Thread.yield()
                }
            }
            try {
                openRecent(wiring, second)
                waitUntil(timeoutMillis = WAIT_MILLIS) { wiring.holdsBranch(SECOND_ONLY_BRANCH) }
            } finally {
                stopSampling.set(true)
                sampler.join()
            }

            sawPreviousRepository.get() shouldBe false
        }
    }
})

/** 화면 컨텍스트가 그 브랜치를 가진 저장소를 가리키고 있는가. */
private fun AppWiring.holdsBranch(branch: String): Boolean =
    currentContext().branches.any { it.name.value.endsWith(branch) }

/** 조립을 띄우고 그 배선 통로를 돌려준다. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.startApp(settingsFile: Path): AppWiring {
    var assembled: AppWiring? = null
    val component = AppComponent(settingsFile, settingsFile.parent)
    setContent {
        AppRoot(component = component, errors = AppErrorState(), onAssembled = { assembled = it })
    }
    waitUntil(timeoutMillis = WAIT_MILLIS) { assembled != null }
    return assembled.shouldNotBeNull()
}

/**
 * 사용자가 하는 그대로 최근 목록에서 저장소를 열고, 저장소 화면이 그려질 때까지 기다린다.
 *
 * 여는 것만으로는 화면이 넘어가지 않는다 — 시작 화면은 사용자가 **머무르기로 한** 화면이라
 * 배선이 목적지를 대신 바꾸지 않는다. 그래서 여기서 저장소 화면을 요청하고, 열림이 실제로 반영돼
 * 그 화면이 그려질 때까지 기다린다.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openRecent(wiring: AppWiring, repository: File) {
    onNodeWithTag(WelcomeTags.recentRow(RepositoryPath(repository.path))).performClick()
    wiring.navigation.go(AppDestination.REPOSITORY)
    waitUntil(timeoutMillis = WAIT_MILLIS) {
        onAllNodesWithTag(AppDestinationTags.of(AppDestination.REPOSITORY)).fetchSemanticsNodes().isNotEmpty()
    }
}

/** 앱이 쓰는 그 경로로 저장소를 열어 최근 목록에 남긴다 — 시작 화면이 이 목록을 그린다. */
private suspend fun rememberAsRecent(settingsFile: Path, vararg repositories: File) {
    val setup = AppComponent(settingsFile, settingsFile.parent)
    repositories.forEach { repository ->
        setup.welcomeActions.openRepository.execute(RepositoryPath(repository.path))
    }
    setup.closeRepository()
}

/** 그래프가 그릴 커밋. 행 태그가 이 값으로 만들어진다. */
private fun headCommitOf(repository: File): CommitId =
    Git.open(repository).use { opened -> CommitId.of(requireNotNull(opened.repository.resolve("HEAD")).name) }
