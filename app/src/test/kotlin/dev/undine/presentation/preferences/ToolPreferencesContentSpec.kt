package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.externaltool.OpenDiffToolUseCase
import dev.undine.application.externaltool.OpenMergeToolUseCase
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.SettingsPreference
import dev.undine.domain.externaltool.DiffToolInput
import dev.undine.domain.externaltool.DiffToolResult
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.externaltool.MergeToolInput
import dev.undine.domain.externaltool.MergeToolResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private val TEXTS = PREFERENCES_TEST_STRINGS

private val MELD = ExternalTool(executable = "meld", arguments = listOf("\$LOCAL", "\$REMOTE"))
private val KDIFF = ExternalTool(executable = "kdiff3", arguments = listOf("\$LOCAL", "\$REMOTE", "\$MERGED"))

/** 저장된 값을 들고 있는 가짜 Gateway. 실패를 켜면 쓰기가 [IOException] 을 던진다. */
private class ToolSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var saveFailure: IOException? = null

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        saveFailure?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        val updated = transform(stored)
        saveFailure?.let { throw it }
        stored = updated
    }
}

/**
 * 실행 파일 존재 여부만 답하는 가짜 도구 Gateway. 실제 프로세스 실행 경계는
 * `ExternalToolGatewayImplSpec` 이 검증하므로 여기서는 판정 결과만 준다.
 */
private class FakeExternalToolGateway(private val installed: Set<String>) : ExternalToolGateway {

    override suspend fun openDiff(input: DiffToolInput): DiffToolResult = DiffToolResult.Completed

    override suspend fun openMerge(input: MergeToolInput): MergeToolResult = MergeToolResult.Unchanged

    override suspend fun isToolAvailable(executable: String): Boolean = executable in installed
}

private class ToolFixture(initial: Settings = Settings.DEFAULTS) {
    val gateway = ToolSettingsGateway(initial)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    ).also(PreferencesState::refresh)
}

private val TAB_WIDTH = 900.dp
private val TAB_HEIGHT = 700.dp
private val CATALOG = builtInStringCatalog()

/** 다른 탭 테스트와 같은 형태로 실제 조립을 그린다 — 상태 홀더만 보면 조립 회귀를 놓친다. */
@Composable
private fun ToolTabHost(state: PreferencesState, tools: ExternalToolUseCases) {
    CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
        UndineTheme {
            ToolPreferencesContent(
                state = state,
                texts = TEXTS,
                externalTools = tools,
                modifier = Modifier.size(TAB_WIDTH, TAB_HEIGHT),
            )
        }
    }
}

private fun availability(vararg installed: String): CheckToolAvailabilityUseCase =
    CheckToolAvailabilityUseCase(FakeExternalToolGateway(installed.toSet()))

/**
 * 도구 탭의 상태·값 경로와 **실제 Compose 조립**을 검증한다.
 *
 * 순수 상태 검증만으로는 "상태는 맞는데 화면이 그것을 그리지 않는" 조립 회귀를 놓친다. 같은 wave 의
 * 다른 탭(`AdvancedPreferencesContentSpec`)과 레포의 기존 화면 테스트 13개가 이미
 * `runComposeUiTest` 로 조립을 확인한다 — 같은 형태를 쓴다.
 */
class ToolPreferencesContentSpec : FunSpec({

    @OptIn(ExperimentalTestApi::class)
    test("도구 탭을 그리면 명령·탭 폭·서체 입력이 저장된 값으로 조립된다") {
        runComposeUiTest {
            val fixture = ToolFixture(
                Settings.DEFAULTS.copy(
                    externalTools = ExternalToolSettings(diffTool = MELD, mergeTool = KDIFF),
                    tabWidth = 8,
                ),
            )
            val tools = ExternalToolUseCases(
                openDiff = OpenDiffToolUseCase(FakeExternalToolGateway(emptySet())),
                openMerge = OpenMergeToolUseCase(FakeExternalToolGateway(emptySet())),
                checkAvailability = availability("meld"),
            )

            setContent { ToolTabHost(fixture.state(), tools) }
            waitForIdle()

            onNodeWithTag(ToolPreferencesTags.DIFF_COMMAND).assertIsDisplayed()
            onNodeWithTag(ToolPreferencesTags.MERGE_COMMAND).assertIsDisplayed()
            onNodeWithTag(ToolPreferencesTags.TAB_WIDTH).assertIsDisplayed()
            onNodeWithTag(ToolPreferencesTags.MONOSPACE_FONT).assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    test("탭 폭에 0 을 넣으면 저장되지 않고 저장된 값이 그대로 남는다") {
        runComposeUiTest {
            val fixture = ToolFixture(Settings.DEFAULTS.copy(tabWidth = 8))
            val tools = ExternalToolUseCases(
                openDiff = OpenDiffToolUseCase(FakeExternalToolGateway(emptySet())),
                openMerge = OpenMergeToolUseCase(FakeExternalToolGateway(emptySet())),
                checkAvailability = availability(),
            )
            val state = fixture.state()

            setContent { ToolTabHost(state, tools) }
            waitForIdle()
            onNodeWithTag(ToolPreferencesTags.TAB_WIDTH).performTextReplacement("0")
            waitForIdle()

            // 낙관적으로 그리지 않는다 (결정 G12) — 거부된 값이 화면에도 파일에도 남지 않는다.
            state.settings.tabWidth shouldBe 8
            fixture.gateway.stored.tabWidth shouldBe 8
        }
    }

    test("도구가 설정되지 않은 것은 실패가 아니라 정상 상태로 보인다") {
        val rows = toolPreferencesRows(Settings.DEFAULTS, TEXTS, missing = emptySet())

        rows.map(PreferencesRow::label) shouldContainExactly listOf(
            TEXTS.diffTool,
            TEXTS.mergeTool,
            TEXTS.tabWidth,
            TEXTS.monospaceFont,
        )
        rows[0].value shouldBe TEXTS.toolUnset
        rows[1].value shouldBe TEXTS.toolUnset
        rows[3].value shouldBe TEXTS.monospaceFontSystem
        ExternalToolKind.DIFF.toolIn(Settings.DEFAULTS).draftText() shouldBe ""
    }

    test("탭의 모든 행은 앱 설정 출처다 — git 설정 실효값·출처는 이 탭이 표시하지 않는다") {
        val settings = Settings.DEFAULTS.copy(
            externalTools = ExternalToolSettings(diffTool = MELD, mergeTool = KDIFF),
        )

        val rows = toolPreferencesRows(settings, TEXTS, missing = setOf(ExternalToolKind.DIFF))

        rows.map(PreferencesRow::source).toSet() shouldBe setOf(PreferenceValueSource.APP_SETTINGS)
        rows.map(PreferencesRow::sourceLabel).toSet() shouldBe setOf(TEXTS.sourceApp)
    }

    test("사용자 지정 명령은 실행 파일과 인자 배열로 나뉘어 앱 설정에 저장된다") {
        val fixture = ToolFixture()
        val state = fixture.state()

        state.applyToolCommand(ExternalToolKind.DIFF, "meld \$LOCAL \$REMOTE")
        state.applyToolCommand(ExternalToolKind.MERGE, "kdiff3 \$LOCAL \$REMOTE \$MERGED")

        fixture.gateway.stored.externalTools.diffTool shouldBe MELD
        fixture.gateway.stored.externalTools.mergeTool shouldBe KDIFF
        state.settings.externalTools.diffTool shouldBe MELD
        state.saveFailure.shouldBeNull()
    }

    test("명령을 비우면 그 도구만 설정되지 않음으로 돌아간다") {
        val fixture = ToolFixture(
            Settings.DEFAULTS.copy(externalTools = ExternalToolSettings(diffTool = MELD, mergeTool = KDIFF)),
        )
        val state = fixture.state()

        state.applyToolCommand(ExternalToolKind.DIFF, "   ")

        fixture.gateway.stored.externalTools.diffTool.shouldBeNull()
        fixture.gateway.stored.externalTools.mergeTool shouldBe KDIFF
    }

    test("도구 항목별 기본값 복원은 그 도구만 지우고 다른 도구는 그대로 둔다") {
        val fixture = ToolFixture(
            Settings.DEFAULTS.copy(externalTools = ExternalToolSettings(diffTool = MELD, mergeTool = KDIFF)),
        )
        val state = fixture.state()

        state.restoreDefaultTool(ExternalToolKind.MERGE)

        fixture.gateway.stored.externalTools.mergeTool.shouldBeNull()
        fixture.gateway.stored.externalTools.diffTool shouldBe MELD
    }

    test("탭 폭은 양수만 저장된다") {
        val fixture = ToolFixture()
        val state = fixture.state()

        state.applyTabWidth("2")

        fixture.gateway.stored.tabWidth shouldBe 2
        state.settings.tabWidth shouldBe 2
        state.saveFailure.shouldBeNull()
    }

    test("0 이하의 탭 폭은 domain 이 거부해 저장되지 않고 화면은 저장된 값에 머문다") {
        val fixture = ToolFixture(Settings.DEFAULTS.copy(tabWidth = 2))
        val state = fixture.state()

        state.applyTabWidth("0")

        fixture.gateway.stored.tabWidth shouldBe 2
        state.settings.tabWidth shouldBe 2
        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
    }

    test("숫자가 아닌 탭 폭도 같은 거부 경로로 끝난다 — 범위 판정은 domain 한 곳이다") {
        val fixture = ToolFixture(Settings.DEFAULTS.copy(tabWidth = 8))
        val state = fixture.state()

        state.applyTabWidth("넷")

        fixture.gateway.stored.tabWidth shouldBe 8
        state.settings.tabWidth shouldBe 8
        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
    }

    test("서체 이름은 그대로 저장되고 비우면 시스템 기본값으로 돌아간다") {
        val fixture = ToolFixture()
        val state = fixture.state()

        state.applyMonospaceFont("JetBrains Mono")
        fixture.gateway.stored.monospaceFontFamily shouldBe "JetBrains Mono"

        state.applyMonospaceFont("   ")
        fixture.gateway.stored.monospaceFontFamily.shouldBeNull()
    }

    test("서체·탭 폭의 항목별 기본값 복원은 domain 기본값을 그대로 쓴다") {
        val fixture = ToolFixture(
            Settings.DEFAULTS.copy(tabWidth = 8, monospaceFontFamily = "Fira Code"),
        )
        val state = fixture.state()

        state.restoreDefault(SettingsPreference.TAB_WIDTH)
        state.restoreDefault(SettingsPreference.MONOSPACE_FONT)

        fixture.gateway.stored.tabWidth shouldBe Settings.DEFAULT_TAB_WIDTH
        fixture.gateway.stored.monospaceFontFamily.shouldBeNull()
    }

    test("저장에 실패하면 화면은 저장된 값에 머물고 실패 사유를 남긴다") {
        val fixture = ToolFixture(Settings.DEFAULTS.copy(tabWidth = 2))
        val state = fixture.state()
        fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

        state.applyTabWidth("6")

        fixture.gateway.stored.tabWidth shouldBe 2
        state.settings.tabWidth shouldBe 2
        state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
    }

    test("실행 파일을 찾지 못해도 저장은 유지되고 앱 설정 값 옆에 찾을 수 없음이 붙는다") {
        val fixture = ToolFixture()
        val state = fixture.state()

        state.applyToolCommand(ExternalToolKind.DIFF, "meld \$LOCAL \$REMOTE")
        val missing = missingToolExecutables(state.settings, availability("kdiff3"))

        fixture.gateway.stored.externalTools.diffTool shouldBe MELD
        state.saveFailure.shouldBeNull()
        missing shouldBe setOf(ExternalToolKind.DIFF)
        val row = toolPreferencesRows(state.settings, TEXTS, missing).first()
        row.value shouldContain "meld"
        row.value shouldContain TEXTS.executableNotFound
        row.source shouldBe PreferenceValueSource.APP_SETTINGS
    }

    test("설치된 실행 파일에는 찾을 수 없음을 붙이지 않는다") {
        val settings = Settings.DEFAULTS.copy(
            externalTools = ExternalToolSettings(diffTool = MELD, mergeTool = KDIFF),
        )

        val missing = missingToolExecutables(settings, availability("meld", "kdiff3"))

        missing shouldBe emptySet()
        toolPreferencesRows(settings, TEXTS, missing).first().value shouldBe "meld \$LOCAL \$REMOTE"
    }

    test("설정되지 않은 도구는 존재 확인 대상이 아니다") {
        missingToolExecutables(Settings.DEFAULTS, availability()) shouldBe emptySet()
    }

    test("Enter 는 포커스 이탈과 같은 확정 경로다") {
        isToolEditorCommitKey(Key.Enter, KeyEventType.KeyDown) shouldBe true
        isToolEditorCommitKey(Key.NumPadEnter, KeyEventType.KeyDown) shouldBe true
        isToolEditorCommitKey(Key.Enter, KeyEventType.KeyUp) shouldBe false
        isToolEditorCommitKey(Key.A, KeyEventType.KeyDown) shouldBe false

        val committed = mutableListOf<String>()
        commitDraft(draft = "meld", saved = "", onCommit = committed::add)
        commitDraft(draft = "meld", saved = "meld", onCommit = committed::add)

        committed shouldContainExactly listOf("meld")
    }

})
