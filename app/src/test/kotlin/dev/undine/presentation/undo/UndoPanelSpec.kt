package dev.undine.presentation.undo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.undo.DiscardBlockedUndoEntryUseCase
import dev.undine.application.undo.LoadUndoHistoryUseCase
import dev.undine.application.undo.PeekUndoTargetUseCase
import dev.undine.application.undo.UndoLastOperationUseCase
import dev.undine.application.undo.UndoService
import dev.undine.domain.Branch
import dev.undine.domain.CommitId
import dev.undine.domain.RefGateway
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryBaseline
import dev.undine.domain.RepositoryGateway
import dev.undine.domain.ResetMode
import dev.undine.domain.ThemeMode
import dev.undine.domain.WorkingTreeStatus
import dev.undine.domain.WorktreeOpsGateway
import dev.undine.domain.undo.GitOperationKind
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoStack
import dev.undine.domain.undo.UndoStrategy
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.undoTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import java.time.Instant

private val MAIN = RefName("main")
private val HEAD = CommitId.of("a".repeat(40))
private val PARENT = CommitId.of("b".repeat(40))

private const val TOOLTIP_DELAY_MILLIS = 2_000L

private fun undoEntry() = OperationEntry(
    operation = GitOperationKind.COMMIT,
    strategy = UndoStrategy.SoftResetTo(PARENT),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "로그인 수정",
    recordedAt = Instant.parse("2026-08-25T01:02:03Z"),
)

private fun irreversibleEntry() = OperationEntry(
    operation = GitOperationKind.PUSH,
    strategy = UndoStrategy.Irreversible("원격에 이미 반영됨"),
    baseline = RepositoryBaseline(branch = MAIN, head = HEAD),
    targetLabel = "origin/main",
    recordedAt = Instant.parse("2026-08-25T01:02:04Z"),
)

private class PanelFixture(vararg recorded: OperationEntry) {
    val stack = UndoStack()
    val refGateway = mockk<RefGateway>()
    val repositoryGateway = mockk<RepositoryGateway>()
    val worktreeOpsGateway = mockk<WorktreeOpsGateway>()

    init {
        coEvery { refGateway.listBranches() } returns listOf(
            Branch(MAIN, HEAD, isCurrent = true, isRemote = false, upstream = null, ahead = 0, behind = 0),
        )
        coEvery { repositoryGateway.status() } returns
            WorkingTreeStatus(emptyList(), emptyList(), emptyList(), emptyList())
        coEvery { worktreeOpsGateway.reset(any(), any()) } just Runs
        recorded.forEach(stack::record)
    }

    private val service = UndoService(stack, refGateway, repositoryGateway, worktreeOpsGateway)
    val peek = PeekUndoTargetUseCase(service)
    val history = LoadUndoHistoryUseCase(stack)
    val undo = UndoLastOperationUseCase(service)
    val discard = DiscardBlockedUndoEntryUseCase(service)
}

/**
 * Undo 화면 렌더링 — 툴팁이 **가리켰을 때만** 뜨는지, 막힌 기록을 지우는 경로가 화면에 있는지.
 *
 * 상태 전이는 `UndoStateSpec` 이 본다. 여기서는 표시 모델이 실제 UI 요소로 연결됐는지만 확인한다.
 */
@OptIn(ExperimentalTestApi::class)
class UndoPanelSpec : FunSpec({

    test("대상 툴팁은 상시 노출이 아니라 버튼을 가리켰을 때 뜬다") {
        runComposeUiTest {
            val fixture = PanelFixture(undoEntry())
            setContent { UndoPanelUnderTest(fixture) }
            waitForIdle()

            // 버튼 아래 설명문으로 늘 떠 있으면 그건 툴팁이 아니다.
            onNodeWithTag(UndoTags.TOOLTIP).assertDoesNotExist()

            onNodeWithTag(UndoTags.BUTTON).performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(TOOLTIP_DELAY_MILLIS)
            waitForIdle()

            onNodeWithTag(UndoTags.TOOLTIP).assertTextContains("로그인 수정", substring = true)
        }
    }

    test("되돌릴 수 없는 최상단은 지우기 버튼을 내주고, 누르면 그 아래 기록을 되돌릴 수 있다") {
        runComposeUiTest {
            val fixture = PanelFixture(undoEntry(), irreversibleEntry())
            setContent { UndoPanelUnderTest(fixture) }
            waitForIdle()

            onNodeWithTag(UndoTags.DISCARD).performClick()
            waitForIdle()

            fixture.stack.size shouldBe 1

            onNodeWithTag(UndoTags.BUTTON).performClick()
            waitForIdle()

            coVerify(exactly = 1) { fixture.worktreeOpsGateway.reset(PARENT, ResetMode.SOFT) }
            fixture.stack.size shouldBe 0
        }
    }

    test("되돌릴 수 있는 최상단에는 지우기 버튼을 내주지 않는다") {
        runComposeUiTest {
            val fixture = PanelFixture(undoEntry())
            setContent { UndoPanelUnderTest(fixture) }
            waitForIdle()

            onNodeWithTag(UndoTags.DISCARD).assertDoesNotExist()
        }
    }
})

/** 테마와 문자열 카탈로그를 갖춘 최소 호스트. 실제 앱 조립은 UND-51 이 맡는다. */
@Composable
private fun UndoPanelUnderTest(fixture: PanelFixture) {
    val catalog = StringCatalog(
        translations = mergeTranslations(listOf(commonTranslations, undoTranslations)),
        defaultLocale = DEFAULT_LOCALE,
    )
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
            UndoPanel(
                state = rememberUndoState(fixture.peek, fixture.history, fixture.undo, fixture.discard),
                modifier = Modifier,
            )
        }
    }
}
