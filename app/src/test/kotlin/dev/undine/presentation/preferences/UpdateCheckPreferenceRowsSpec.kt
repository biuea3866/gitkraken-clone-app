package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.preferences.AppliedSettings
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.UpdateCheckSettings
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.UpdateStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.update
import dev.undine.testsupport.inlineTestScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Locale

private val TAB_WIDTH = 900.dp
private val TAB_HEIGHT = 700.dp

private val TEXTS = PREFERENCES_TEST_STRINGS

private val CATALOG = builtInStringCatalog()

private val UPDATE_TEXTS: UpdateStrings = CATALOG.stringsFor(Locale.KOREAN, devBuild = false).update

/** 저장된 값을 들고 있는 가짜 Gateway. 설정이 실제로 파일 쪽까지 닿았는지 대조한다. */
private class UpdateCheckSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        stored = transform(stored)
    }
}

private class Fixture(updateCheck: UpdateCheckSettings = UpdateCheckSettings.DEFAULT) {

    val gateway = UpdateCheckSettingsGateway(Settings.DEFAULTS.copy(updateCheck = updateCheck))

    private val scope = inlineTestScope()

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway, AppliedSettings()),
    ).also(PreferencesState::refresh)
}

/**
 * 두 행을 실제 탭과 같은 세로 배치 안에 놓는다.
 *
 * 배치 없이 그리면 두 행이 같은 자리에 겹쳐 클릭이 엉뚱한 노드로 간다 — 일반 탭에서도 이 행들은
 * `Column` 안에 놓인다.
 */
@Composable
private fun Rows(state: PreferencesState) {
    UndineTheme {
        Column(modifier = Modifier.size(TAB_WIDTH, TAB_HEIGHT)) {
            UpdateCheckPreferenceRows(state = state, texts = TEXTS, updateTexts = UPDATE_TEXTS)
        }
    }
}

/**
 * 두 행이 아니라 **일반 탭 전체**를 그린다.
 *
 * 행만 따로 그리는 테스트는 행이 맞다는 것만 말한다 — 그 행이 일반 탭에 실제로 얹혔는지(결정 D17)는
 * 말하지 않는다. 배선은 조용히 끊긴다.
 */
@Composable
private fun GeneralTab(state: PreferencesState) {
    CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
        UndineTheme {
            Column(modifier = Modifier.size(TAB_WIDTH, TAB_HEIGHT)) {
                GeneralPreferencesContent(state = state, texts = TEXTS)
            }
        }
    }
}

/**
 * 일반 탭에 얹은 자동 업데이트 확인 행 두 개.
 *
 * **기존 `Settings.updateCheck` 만 읽고 쓴다** (결정 D13) — 스키마를 넓히지 않고, 업데이트 전용
 * 탭도 만들지 않는다. 여기서는 그 두 행이 저장된 값을 보여 주고 저장 경로로 들어가는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateCheckPreferenceRowsSpec : FunSpec({

    test("저장된 on/off 와 주기를 그대로 보여 준다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = false, intervalHours = 12))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).assertTextEquals("12")
        }
    }

    test("끄면 설정에 꺼짐으로 저장되고 주기 값은 남는다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 12))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.ENABLED_OFF).performClick()
            waitForIdle()
        }

        fixture.gateway.stored.updateCheck shouldBe UpdateCheckSettings(enabled = false, intervalHours = 12)
    }

    test("켜면 설정에 켜짐으로 저장된다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = false, intervalHours = 6))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.ENABLED_ON).performClick()
            waitForIdle()
        }

        fixture.gateway.stored.updateCheck shouldBe UpdateCheckSettings(enabled = true, intervalHours = 6)
    }

    test("주기를 바꾸면 저장되고 on/off 는 그대로다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 24))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).performTextReplacement("6")
            waitForIdle()
        }

        fixture.gateway.stored.updateCheck shouldBe UpdateCheckSettings(enabled = true, intervalHours = 6)
    }

    test("뜻 없는 주기는 저장하지 않고 입력 오류를 보여 준다") {
        // 저장해 봐야 설정을 읽는 codec 이 기본 주기로 되돌려, 화면과 실제 주기가 갈린다.
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 24))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).performTextReplacement("0")
            waitForIdle()

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL_ERROR).assertTextEquals(TEXTS.invalidValue)
        }

        fixture.gateway.stored.updateCheck.intervalHours shouldBe 24
    }

    test("숫자가 아닌 입력도 저장을 부르지 않는다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 24))

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).performTextReplacement("자주")
            waitForIdle()

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL_ERROR).assertTextEquals(TEXTS.invalidValue)
        }

        fixture.gateway.stored.updateCheck.intervalHours shouldBe 24
    }

    test("유효한 입력에는 오류 문구가 남지 않는다") {
        val fixture = Fixture()

        runComposeUiTest {
            val state = fixture.state()
            setContent { Rows(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).performTextReplacement("168")
            waitForIdle()

            onAllNodesWithTag(UpdateCheckPreferencesTags.INTERVAL_ERROR).fetchSemanticsNodes().shouldBeEmpty()
        }
    }

    test("범위 판정은 domain 이 선언한 범위를 그대로 쓴다") {
        readIntervalHours("1") shouldBe 1
        readIntervalHours("168") shouldBe 168
        readIntervalHours("0").shouldBeNull()
        readIntervalHours("169").shouldBeNull()
        readIntervalHours(" 24 ") shouldBe 24
        readIntervalHours("").shouldBeNull()
    }

    test("일반 탭에 자동 업데이트 확인 행이 실제로 얹혀 있다 — 전용 탭을 만들지 않는다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 6))

        runComposeUiTest {
            val state = fixture.state()
            setContent { GeneralTab(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.INTERVAL).assertTextEquals("6")
            onAllNodesWithTag(UpdateCheckPreferencesTags.ENABLED_ON).fetchSemanticsNodes().shouldHaveSize(1)
            onAllNodesWithTag(UpdateCheckPreferencesTags.ENABLED_OFF).fetchSemanticsNodes().shouldHaveSize(1)
        }
    }

    test("일반 탭에서 끈 값이 설정 저장 경로로 들어간다") {
        val fixture = Fixture(UpdateCheckSettings(enabled = true, intervalHours = 6))

        runComposeUiTest {
            val state = fixture.state()
            setContent { GeneralTab(state) }

            onNodeWithTag(UpdateCheckPreferencesTags.ENABLED_OFF).performClick()
            waitForIdle()

            fixture.gateway.stored.updateCheck shouldBe UpdateCheckSettings(enabled = false, intervalHours = 6)
        }
    }

    test("행은 앱 설정 출처이고 항목별 기본값 복원을 내준다") {
        val settings = Settings.DEFAULTS.copy(updateCheck = UpdateCheckSettings(enabled = false, intervalHours = 3))

        automaticUpdateCheckRow(settings, TEXTS, UPDATE_TEXTS).canRestoreDefault shouldBe true
        automaticUpdateCheckRow(settings, TEXTS, UPDATE_TEXTS).value shouldBe TEXTS.disabled
        updateCheckIntervalRow(settings, TEXTS, UPDATE_TEXTS).value shouldBe "3"
        updateCheckIntervalRow(settings, TEXTS, UPDATE_TEXTS).source shouldBe PreferenceValueSource.APP_SETTINGS
    }
})
