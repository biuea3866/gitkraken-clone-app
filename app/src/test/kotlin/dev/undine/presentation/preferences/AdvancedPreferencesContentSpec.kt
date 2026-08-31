package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.palette.CommandRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private val TAB_WIDTH = 900.dp
private val TAB_HEIGHT = 700.dp

private const val STORED_THRESHOLD_BYTES = 4096L
private const val STORED_PAGE_SIZE = 25

private val CATALOG = builtInStringCatalog()

/** 실제 한국어 문구. 누락 키가 있으면 조회가 키 이름을 돌려줘 눈에 띈다. */
private val TEXTS = PREFERENCES_TEST_STRINGS

private val THRESHOLD_INPUT_ERROR =
    AdvancedPreferencesTags.inputErrorOf(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
private val PAGE_SIZE_INPUT_ERROR =
    AdvancedPreferencesTags.inputErrorOf(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)

/** 저장된 값을 들고 있는 가짜 Gateway. 고급 탭의 저장이 실제로 파일까지 닿았는지 대조한다. */
private class AdvancedSettingsGateway(initial: Settings) : SettingsGateway {

    var stored: Settings = initial
        private set

    var saveFailure: IOException? = null

    /** `update` 가 몇 번 불렸는지. **같은 값으로 저장하는 회귀**는 저장값 비교로는 잡히지 않는다. */
    var updateCalls: Int = 0
        private set

    override suspend fun load(): Settings = stored

    override suspend fun save(settings: Settings) {
        saveFailure?.let { throw it }
        stored = settings
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        updateCalls += 1
        val updated = transform(stored)
        saveFailure?.let { throw it }
        stored = updated
    }
}

private class AdvancedFixture(
    initial: Settings = Settings.DEFAULTS.copy(
        largeFileThresholdBytes = STORED_THRESHOLD_BYTES,
        commitPageSize = STORED_PAGE_SIZE,
    ),
) {
    val gateway = AdvancedSettingsGateway(initial)
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    ).also(PreferencesState::refresh)
}

@Composable
private fun AdvancedTabHost(state: PreferencesState) {
    CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
        UndineTheme {
            AdvancedPreferencesContent(
                state = state,
                texts = TEXTS,
                modifier = Modifier.size(TAB_WIDTH, TAB_HEIGHT),
            )
        }
    }
}

/**
 * 실제 [PreferencesScreen] 을 그리는 숙주. 고급 탭이 셸의 디스패치를 통해 닿는지, 그리고 값 거부가
 * 셸의 공용 저장 실패 자리에 뜨는지는 탭만 그려서는 볼 수 없다.
 *
 * 다른 다섯 탭의 의존은 **전달되기만** 하면 되므로 Mock 이다 — 이 테스트가 보는 경로에서 호출되지
 * 않는다. Git 연산이 아니라 아직 이 탭과 무관한 협력자 자리라 Mock 을 쓸 수 있다.
 */
@Composable
private fun AdvancedScreenHost(state: PreferencesState) {
    CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
        UndineTheme {
            PreferencesScreen(
                state = state,
                dependencies = PreferencesTabDependencies(
                    identity = mockk(relaxed = true),
                    externalTools = mockk(relaxed = true),
                    commands = CommandRegistry(),
                ),
                modifier = Modifier.size(TAB_WIDTH, TAB_HEIGHT),
            )
        }
    }
}

/**
 * 고급 탭 — 대용량 파일 임계치·커밋 페이지 크기의 표시와 즉시 저장.
 *
 * 저장 버튼이 없으므로 보는 것은 **입력이 저장 경로에 닿는가**와 **닿지 못한 입력이 화면 값을
 * 바꾸지 않는가** 다. 허용 범위 판정은 domain 이 소유하므로 여기서는 그 거부가 저장을 막았는지만 본다.
 */
@OptIn(ExperimentalTestApi::class)
class AdvancedPreferencesContentSpec : FunSpec({

    test("환경설정 셸에서 고급 탭을 고르면 두 숫자 행이 그려지고 거부된 값은 셸의 실패 자리에 뜬다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedScreenHost(state) }

            onAllNodesWithTag(PreferencesTags.TAB)[PreferencesTab.ADVANCED.ordinal].performClick()
            waitForIdle()

            // 셸의 디스패치를 거쳐 이 탭이 그려진다 — 자리 표시가 아니라 실제 두 행이다.
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).assertIsDisplayed()
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).assertIsDisplayed()
            onAllNodesWithText(TEXTS.comingSoon).fetchSemanticsNodes().size shouldBe 0

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("0")
            waitForIdle()

            onNodeWithTag(PreferencesTags.SAVE_FAILURE).assertIsDisplayed()
            onNodeWithText(TEXTS.invalidValue).assertIsDisplayed()
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())

            // 거부가 이어져도(사유가 그대로여도) 두 번째 미저장 값이 입력칸에 남지 않는다.
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).performTextReplacement("-1")
            waitForIdle()

            onNodeWithTag(PreferencesTags.SAVE_FAILURE).assertIsDisplayed()
            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(STORED_THRESHOLD_BYTES.toString())
        }
    }

    test("저장된 임계치와 페이지 크기를 각각 앱 설정 행으로 보여준다") {
        runComposeUiTest {
            val state = AdvancedFixture().state()
            setContent { AdvancedTabHost(state) }

            onAllNodesWithTag(PreferencesTags.ROW).fetchSemanticsNodes().size shouldBe 2
            onAllNodesWithText(TEXTS.largeFileThreshold).fetchSemanticsNodes().size shouldBe 1
            onAllNodesWithText(TEXTS.commitPageSize).fetchSemanticsNodes().size shouldBe 1
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(STORED_THRESHOLD_BYTES.toString())
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())
            // 두 행 모두 앱 설정이 실효값이라 출처 표시와 항목별 기본값 복원을 내준다.
            onAllNodesWithTag(PreferencesTags.ROW_RESTORE_DEFAULT).fetchSemanticsNodes().size shouldBe 2
            onAllNodesWithText(TEXTS.sourceApp).fetchSemanticsNodes().size shouldBe 2
        }
    }

    test("양수 임계치를 입력하면 저장 버튼 없이 파일까지 저장되고 화면 값이 따라온다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).performTextReplacement("8192")
            waitForIdle()

            fixture.gateway.stored.largeFileThresholdBytes shouldBe 8192L
            state.settings.largeFileThresholdBytes shouldBe 8192L
            state.saveFailure shouldBe null
            // 임계치만 바뀐다 — 같은 탭의 다른 값을 지우지 않는다.
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
        }
    }

    test("양수 페이지 크기를 입력하면 저장 버튼 없이 파일까지 저장된다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("50")
            waitForIdle()

            fixture.gateway.stored.commitPageSize shouldBe 50
            state.settings.commitPageSize shouldBe 50
            state.saveFailure shouldBe null
            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
        }
    }

    test("0 을 입력하면 domain 이 거부해 저장되지 않고 입력칸은 이전 저장값으로 돌아간다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("0")
            waitForIdle()

            // 값 거부와 쓰기 실패는 사용자가 할 일이 달라 사유 종류까지 본다.
            state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
            state.settings.commitPageSize shouldBe STORED_PAGE_SIZE
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())
            // 값 거부는 공용 저장 실패 자리로 간다 — 탭이 자기 입력 오류로 겹쳐 표시하지 않는다.
            onAllNodesWithTag(PAGE_SIZE_INPUT_ERROR).fetchSemanticsNodes().size shouldBe 0
        }
    }

    test("음수 임계치도 domain 이 거부해 저장되지 않고 입력칸은 이전 저장값으로 돌아간다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).performTextReplacement("-1")
            waitForIdle()

            state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.Rejected>()
            state.settings.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(STORED_THRESHOLD_BYTES.toString())
        }
    }

    test("쓰기 실패 뒤에도 숫자 입력칸은 이전 저장값을 보여준다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }
            fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).performTextReplacement("8192")
            waitForIdle()

            state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
            state.settings.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(STORED_THRESHOLD_BYTES.toString())
        }
    }

    test("같은 사유로 쓰기가 두 번 실패해도 두 번째 미저장 값이 입력칸에 남지 않는다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }
            // 같은 예외가 두 번 던져지면 실패 사유도 같은 값이다 — 실패 표시의 "변화" 를 되돌리기
            // 계기로 삼으면 두 번째 실패에서 계기가 사라진다.
            fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("50")
            waitForIdle()
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("70")
            waitForIdle()

            state.saveFailure.shouldBeInstanceOf<PreferencesSaveFailure.NotWritten>()
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())
        }
    }

    test("숫자로 읽을 수 없는 입력은 저장을 부르지 않고 입력 오류로 표시된다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD).performTextReplacement("팔천")
            waitForIdle()

            onNodeWithTag(THRESHOLD_INPUT_ERROR).assertIsDisplayed()
            // **저장 경로를 아예 부르지 않는다.** 저장값 비교만으로는 같은 값으로 update 를 부르는
            // 회귀를 잡지 못한다 — 호출 자체가 없어야 한다.
            fixture.gateway.updateCalls shouldBe 0
            state.saveFailure shouldBe null
            state.settings.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
        }
    }

    test("빈 입력도 저장을 부르지 않는다 — 지우는 도중을 0 으로 읽지 않는다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("")
            waitForIdle()

            onNodeWithTag(PAGE_SIZE_INPUT_ERROR).assertIsDisplayed()
            fixture.gateway.updateCalls shouldBe 0
            state.saveFailure shouldBe null
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
        }
    }

    test("숫자가 아닌 입력을 고치면 입력 오류가 지워지고 그 값이 저장된다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("스무")
            waitForIdle()
            onNodeWithTag(PAGE_SIZE_INPUT_ERROR).assertIsDisplayed()

            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE).performTextReplacement("20")
            waitForIdle()

            onAllNodesWithTag(PAGE_SIZE_INPUT_ERROR).fetchSemanticsNodes().size shouldBe 0
            fixture.gateway.stored.commitPageSize shouldBe 20
        }
    }

    test("항목별 기본값 복원은 그 행의 값만 되돌리고 입력칸도 그 값으로 맞춰진다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            onAllNodesWithTag(PreferencesTags.ROW_RESTORE_DEFAULT)[0].performClick()
            waitForIdle()

            fixture.gateway.stored.largeFileThresholdBytes shouldBe
                Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES.toString())
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(STORED_PAGE_SIZE.toString())
        }
    }

    test("전체 초기화가 값을 되돌리면 입력칸도 기본값으로 다시 맞춰진다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            state.requestResetAll()
            state.confirmResetAll()
            waitForIdle()

            fixture.gateway.stored.largeFileThresholdBytes shouldBe
                Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES
            fixture.gateway.stored.commitPageSize shouldBe Settings.DEFAULT_COMMIT_PAGE_SIZE
            onNodeWithTag(AdvancedPreferencesTags.LARGE_FILE_THRESHOLD)
                .assertTextEquals(Settings.DEFAULT_LARGE_FILE_THRESHOLD_BYTES.toString())
            onNodeWithTag(AdvancedPreferencesTags.COMMIT_PAGE_SIZE)
                .assertTextEquals(Settings.DEFAULT_COMMIT_PAGE_SIZE.toString())
        }
    }

    test("확인 전 전체 초기화 요청만으로는 고급 값이 되돌아가지 않는다") {
        runComposeUiTest {
            val fixture = AdvancedFixture()
            val state = fixture.state()
            setContent { AdvancedTabHost(state) }

            state.requestResetAll()
            waitForIdle()

            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE

            state.cancelResetAll()
            waitForIdle()

            fixture.gateway.stored.largeFileThresholdBytes shouldBe STORED_THRESHOLD_BYTES
            fixture.gateway.stored.commitPageSize shouldBe STORED_PAGE_SIZE
        }
    }
})
