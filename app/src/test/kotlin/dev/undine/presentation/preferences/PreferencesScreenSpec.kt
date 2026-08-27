package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.application.externaltool.OpenDiffToolUseCase
import dev.undine.application.externaltool.OpenMergeToolUseCase
import dev.undine.application.identity.ApplyProfileUseCase
import dev.undine.application.identity.AssignedProfileNameUseCase
import dev.undine.application.identity.ClearLocalIdentityUseCase
import dev.undine.application.identity.DeleteProfileUseCase
import dev.undine.application.identity.IdentityUseCases
import dev.undine.application.identity.LoadProfilesUseCase
import dev.undine.application.identity.SaveProfileUseCase
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.application.preferences.UpdatePreferencesUseCase
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.domain.externaltool.ExternalToolGateway
import dev.undine.domain.identity.IdentityService
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.palette.CommandRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException

private val SCREEN_WIDTH = 900.dp
private val SCREEN_HEIGHT = 700.dp

private val CATALOG = builtInStringCatalog()

/** 실제 한국어 문구. 누락 키가 있으면 조회가 키 이름을 돌려줘 눈에 띈다 ([PREFERENCES_TEST_STRINGS]). */
private val TEXTS = PREFERENCES_TEST_STRINGS

/**
 * 탭별 의존은 **전달되는지만** 본다 — 스텁이 아직 쓰지 않으므로 동작을 검증할 것이 없다.
 * Mock 은 Git 연산이 아니라 아직 구현되지 않은 탭의 협력자 자리라 쓸 수 있다.
 */
private fun tabDependencies(): PreferencesTabDependencies {
    val identityService = mockk<IdentityService>()
    val externalToolGateway = mockk<ExternalToolGateway>()
    return PreferencesTabDependencies(
        identity = IdentityUseCases(
            loadProfiles = LoadProfilesUseCase(identityService),
            saveProfile = SaveProfileUseCase(identityService),
            deleteProfile = DeleteProfileUseCase(identityService),
            applyProfile = ApplyProfileUseCase(identityService),
            clearLocalIdentity = ClearLocalIdentityUseCase(identityService),
            assignedProfileName = AssignedProfileNameUseCase(mockk()),
        ),
        externalTools = ExternalToolUseCases(
            openDiff = OpenDiffToolUseCase(externalToolGateway),
            openMerge = OpenMergeToolUseCase(externalToolGateway),
            checkAvailability = CheckToolAvailabilityUseCase(externalToolGateway),
        ),
        commands = CommandRegistry(),
    )
}

/** 저장된 값을 들고 있는 가짜 Gateway. 실패를 켜면 쓰기가 [IOException] 을 던진다. */
private class ScreenSettingsGateway(initial: Settings) : SettingsGateway {

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

private class ScreenFixture(initial: Settings = Settings.DEFAULTS.copy(theme = ThemeMode.DARK)) {
    val gateway = ScreenSettingsGateway(initial)
    val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    fun state(): PreferencesState = PreferencesState(
        scope = scope,
        loadPreferences = LoadPreferencesUseCase(gateway),
        updatePreferences = UpdatePreferencesUseCase(gateway),
    ).also(PreferencesState::refresh)
}

@Composable
private fun PreferencesHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
        UndineTheme { content() }
    }
}

/**
 * 환경설정 셸 렌더링 — 여섯 탭 디스패치와 저장 실패 표시.
 *
 * 탭 내용은 후속 티켓이 채우므로 여기서 보는 것은 **경계**다: 선택한 탭 하나만 그려지는가,
 * 여섯 탭이 모두 같은 호출로 닿는가, 저장이 거부·실패했을 때 화면이 저장된 값에 머무는가.
 */
@OptIn(ExperimentalTestApi::class)
class PreferencesScreenSpec : FunSpec({

    test("여섯 탭이 모두 선택되고 그때마다 그 탭의 내용 하나만 그려진다") {
        runComposeUiTest {
            val state = ScreenFixture().state()
            setContent {
                PreferencesHost {
                    PreferencesScreen(
                        state = state,
                        dependencies = tabDependencies(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onAllNodesWithTag(PreferencesTags.TAB).fetchSemanticsNodes().size shouldBe
                PreferencesTab.entries.size

            PreferencesTab.entries.forEachIndexed { index, tab ->
                onAllNodesWithTag(PreferencesTags.TAB)[index].performClick()
                waitForIdle()

                state.selectedTab shouldBe tab
                onNodeWithTag(PreferencesTags.CONTENT).assertIsDisplayed()
                // **어느 탭이 내용을 채웠는지 열거하지 않는다.** 열거하면 탭이 자기 내용을 채울
                // 때마다 이 공용 파일을 고쳐야 해 같은 wave 의 탭 티켓들이 서로 충돌한다.
                // 검증할 것은 "한 탭만 컴포지션에 들어간다" 이므로, 자리 표시가 둘 이상 보이지
                // 않는 것으로 확인한다 — 채운 탭은 0, 스텁 탭은 1 이고 둘 다 이 단언을 만족한다.
                onAllNodesWithText(TEXTS.comingSoon).fetchSemanticsNodes().size shouldBeLessThanOrEqual 1
            }
        }
    }

    test("허용 범위를 벗어난 값을 넣으면 입력 오류가 뜨고 화면 값이 그대로다") {
        runComposeUiTest {
            val fixture = ScreenFixture()
            val state = fixture.state()
            setContent {
                PreferencesHost {
                    PreferencesScreen(
                        state = state,
                        dependencies = tabDependencies(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            state.apply { it.copy(commitPageSize = 0) }
            waitForIdle()

            onNodeWithTag(PreferencesTags.SAVE_FAILURE).assertIsDisplayed()
            onNodeWithText(TEXTS.invalidValue).assertIsDisplayed()
            state.settings.commitPageSize shouldBe Settings.DEFAULT_COMMIT_PAGE_SIZE
            fixture.gateway.stored.commitPageSize shouldBe Settings.DEFAULT_COMMIT_PAGE_SIZE
        }
    }

    test("저장에 실패하면 쓰기 실패 문구가 뜨고 화면은 저장된 이전 값에 머문다") {
        runComposeUiTest {
            val fixture = ScreenFixture()
            val state = fixture.state()
            setContent {
                PreferencesHost {
                    PreferencesScreen(
                        state = state,
                        dependencies = tabDependencies(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }
            fixture.gateway.saveFailure = IOException("디스크가 가득 찼습니다")

            state.apply { it.copy(theme = ThemeMode.LIGHT) }
            waitForIdle()

            onNodeWithText(TEXTS.saveFailed).assertIsDisplayed()
            state.settings.theme shouldBe ThemeMode.DARK
            fixture.gateway.stored.theme shouldBe ThemeMode.DARK
        }
    }

    test("탭 값을 올바르게 바꾸면 실패 표시 없이 저장된다") {
        runComposeUiTest {
            val fixture = ScreenFixture()
            val state = fixture.state()
            setContent {
                PreferencesHost {
                    PreferencesScreen(
                        state = state,
                        dependencies = tabDependencies(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            state.apply { it.copy(defaultBranchName = "trunk", tabWidth = 2) }
            waitForIdle()

            onAllNodesWithTag(PreferencesTags.SAVE_FAILURE).fetchSemanticsNodes().size shouldBe 0
            fixture.gateway.stored.defaultBranchName shouldBe "trunk"
            fixture.gateway.stored.tabWidth shouldBe 2
        }
    }
})
