package dev.undine.presentation.palette

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.domain.ThemeMode
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.paletteTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val BLOCK_REASON = "저장소를 먼저 열어야 합니다"
private const val SHORTCUT_HOST = "test.shortcutHost"

private val PALETTE_WIDTH = 480.dp
private val PALETTE_HEIGHT = 360.dp

/** 팔레트 화면 — 후보 렌더링·비활성 사유·OS 수식키 표기·빈 상태·단축키 입력. */
@OptIn(ExperimentalTestApi::class)
class CommandPaletteSpec : FunSpec({

    test("실행 가능한 후보와 비활성 후보가 사유와 함께 그려진다") {
        runComposeUiTest {
            val state = paletteStateOf(
                testCommand("branch.create", "Create Branch"),
                testCommand(
                    id = "commit.create",
                    title = "Commit Changes",
                    availability = { CommandAvailability.Blocked(BLOCK_REASON) },
                ),
            )
            setContent { PaletteUnderTest(state) }

            onNodeWithTag(PaletteTags.row(CommandId("branch.create"))).assertIsDisplayed()
            onNodeWithTag(PaletteTags.row(CommandId("commit.create"))).assertIsDisplayed()
            onNodeWithText("Create Branch").assertIsDisplayed()
            onNodeWithText("Commit Changes").assertIsDisplayed()
            // 사유는 행(clickable) 안에 있어 시맨틱이 병합된다 — 태그 자체는 unmerged 트리에서 집는다.
            onNodeWithTag(PaletteTags.reason(CommandId("commit.create")), useUnmergedTree = true)
                .assertIsDisplayed()
            onNodeWithText(BLOCK_REASON).assertIsDisplayed()
        }
    }

    test("실행 가능한 후보에는 비활성 사유가 붙지 않는다") {
        runComposeUiTest {
            val state = paletteStateOf(testCommand("branch.create", "Create Branch"))
            setContent { PaletteUnderTest(state) }

            // unmerged 트리로 봐야 "사유 노드가 아예 없다" 를 실제로 증명한다.
            onNodeWithTag(PaletteTags.reason(CommandId("branch.create")), useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }

    test("수식키는 실행 OS 표기로 행에 표시된다") {
        runComposeUiTest {
            val command = testCommand("branch.create", "Create Branch", shortcut = primaryShortcut(Key.B))
            val state = paletteStateOf(command, platform = ShortcutPlatform.MACOS)
            setContent { PaletteUnderTest(state) }

            onNodeWithText("⌘B").assertIsDisplayed()
        }

        runComposeUiTest {
            val command = testCommand("branch.create", "Create Branch", shortcut = primaryShortcut(Key.B))
            val state = paletteStateOf(command, platform = ShortcutPlatform.OTHER)
            setContent { PaletteUnderTest(state) }

            onNodeWithText("Ctrl+B").assertIsDisplayed()
        }
    }

    test("등록된 명령이 0건이면 빈 상태 안내를 표시한다") {
        runComposeUiTest {
            setContent { PaletteUnderTest(paletteStateOf()) }

            onNodeWithTag(PaletteTags.EMPTY).assertIsDisplayed()
            onNodeWithText("등록된 명령이 없습니다").assertIsDisplayed()
            onNodeWithTag(PaletteTags.LIST).assertDoesNotExist()
        }
    }

    test("검색 결과가 없으면 결과 없음 안내를 표시한다") {
        runComposeUiTest {
            val state = paletteStateOf(testCommand("branch.create", "Create Branch"))
            state.query = "rebase"
            setContent { PaletteUnderTest(state) }

            onNodeWithTag(PaletteTags.EMPTY).assertIsDisplayed()
            onNodeWithText("일치하는 명령이 없습니다").assertIsDisplayed()
        }
    }

    test("후보 행을 클릭하면 명령이 실행되고 최근 목록에 기록된다") {
        runComposeUiTest {
            var executed = 0
            val outcomes = mutableListOf<CommandOutcome>()
            val command = testCommand("branch.create", "Create Branch", action = { executed++ })
            val state = paletteStateOf(command)
            state.open()
            setContent { PaletteUnderTest(state, onOutcome = outcomes::add) }

            onNodeWithTag(PaletteTags.row(CommandId("branch.create"))).performClick()
            waitForIdle()

            executed shouldBe 1
            outcomes shouldContainExactly listOf(CommandOutcome.Executed(CommandId("branch.create")))
            state.recentCommandIds shouldContainExactly listOf(CommandId("branch.create"))
            state.isOpen shouldBe false
        }
    }

    test("비활성 후보 행을 클릭해도 실행되지 않고 사유가 남는다") {
        runComposeUiTest {
            var executed = 0
            val outcomes = mutableListOf<CommandOutcome>()
            val command = testCommand(
                id = "commit.create",
                title = "Commit Changes",
                availability = { CommandAvailability.Blocked(BLOCK_REASON) },
                action = { executed++ },
            )
            val state = paletteStateOf(command)
            state.open()
            setContent { PaletteUnderTest(state, onOutcome = outcomes::add) }

            onNodeWithTag(PaletteTags.row(CommandId("commit.create"))).performClick()
            waitForIdle()

            executed shouldBe 0
            outcomes shouldContainExactly listOf(CommandOutcome.Blocked(CommandId("commit.create"), BLOCK_REASON))
            state.isOpen shouldBe true
            onNodeWithText(BLOCK_REASON).assertIsDisplayed()
        }
    }

    test("등록된 단축키를 누르면 명령이 실행된다") {
        runComposeUiTest {
            var executed = 0
            val outcomes = mutableListOf<CommandOutcome>()
            val command = testCommand("branch.create", shortcut = primaryShortcut(Key.K), action = { executed++ })
            val handler = handlerOf(command, platform = ShortcutPlatform.OTHER)
            setContent { ShortcutHostUnderTest(handler, onOutcome = outcomes::add) }

            onNodeWithTag(SHORTCUT_HOST).requestFocus()
            onNodeWithTag(SHORTCUT_HOST).performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.K)
                keyUp(Key.CtrlLeft)
            }
            waitForIdle()

            executed shouldBe 1
            outcomes shouldContainExactly listOf(CommandOutcome.Executed(CommandId("branch.create")))
        }
    }

    test("수식키 없이 같은 키만 누르면 명령이 실행되지 않는다") {
        runComposeUiTest {
            var executed = 0
            val outcomes = mutableListOf<CommandOutcome>()
            val command = testCommand("branch.create", shortcut = primaryShortcut(Key.K), action = { executed++ })
            val handler = handlerOf(command, platform = ShortcutPlatform.OTHER)
            setContent { ShortcutHostUnderTest(handler, onOutcome = outcomes::add) }

            onNodeWithTag(SHORTCUT_HOST).requestFocus()
            onNodeWithTag(SHORTCUT_HOST).performKeyInput { pressKey(Key.K) }
            waitForIdle()

            executed shouldBe 0
            outcomes shouldContainExactly emptyList()
        }
    }

    test("실행 중 오류는 팔레트를 닫고 실패 결과로 알린다") {
        runComposeUiTest {
            val outcomes = mutableListOf<CommandOutcome>()
            val command = testCommand("remote.fetch", "Fetch", action = { error("원격에 연결하지 못했습니다") })
            val state = paletteStateOf(command)
            state.open()
            setContent { PaletteUnderTest(state, onOutcome = outcomes::add) }

            onNodeWithTag(PaletteTags.row(CommandId("remote.fetch"))).performClick()
            waitForIdle()

            outcomes.single().shouldBeInstanceOf<CommandOutcome.Failed>()
            state.isOpen shouldBe false
        }
    }
})

@Composable
private fun PaletteUnderTest(
    state: CommandPaletteState,
    onOutcome: (CommandOutcome) -> Unit = {},
) {
    PaletteHost {
        CommandPalette(
            state = state,
            modifier = Modifier.size(PALETTE_WIDTH, PALETTE_HEIGHT),
            onOutcome = onOutcome,
        )
    }
}

/** 단축키 입력 경로만 보는 최소 호스트 — 팔레트 없이 [Modifier.commandShortcuts] 만 붙인다. */
@Composable
private fun ShortcutHostUnderTest(
    handler: ShortcutHandler,
    onOutcome: (CommandOutcome) -> Unit,
) {
    PaletteHost {
        Box(
            Modifier
                .size(PALETTE_WIDTH, PALETTE_HEIGHT)
                .testTag(SHORTCUT_HOST)
                .focusable()
                .commandShortcuts(handler, onOutcome),
        ) {
            Box(Modifier.fillMaxSize())
        }
    }
}

/** 테마와 문자열 카탈로그를 갖춘 최소 호스트. palette 네임스페이스는 아직 내장 목록에 없어 직접 만든다. */
@Composable
private fun PaletteHost(content: @Composable () -> Unit) {
    val catalog = StringCatalog(translations = paletteTranslations, defaultLocale = DEFAULT_LOCALE)
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}
