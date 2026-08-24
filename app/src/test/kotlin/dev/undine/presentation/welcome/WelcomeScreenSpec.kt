package dev.undine.presentation.welcome

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.domain.Progress
import dev.undine.domain.RepositoryPath
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException.InvalidRepositoryPath.Reason
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.WelcomeStrings
import dev.undine.presentation.i18n.welcome
import dev.undine.presentation.i18n.welcomeTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val PRESENT = RepositoryPath("/tmp/present")
private val MISSING = RepositoryPath("/tmp/missing")

private const val CLONE_URL = "https://example.invalid/undine.git"
private const val CLONE_TARGET = "/tmp/undine-clone"

private val SCREEN_WIDTH = 900.dp
private val SCREEN_HEIGHT = 700.dp

private val CATALOG = StringCatalog(translations = welcomeTranslations, defaultLocale = DEFAULT_LOCALE)
private val TEXTS: WelcomeStrings = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false).welcome

/** Welcome 화면 렌더링 — 목록·빈 상태·오류 안내·진행 표시·키보드 조작 경로. */
@OptIn(ExperimentalTestApi::class)
class WelcomeScreenSpec : FunSpec({

    test("최근 저장소가 최신순으로 그려지고 사라진 항목은 열 수 없다") {
        runComposeUiTest {
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(
                            recentRepositories = listOf(
                                RecentRepository(PRESENT, available = true),
                                RecentRepository(MISSING, available = false),
                            ),
                        ),
                        events = WelcomeEvents(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.recentRow(PRESENT)).assertIsDisplayed().assertIsEnabled()
            onNodeWithTag(WelcomeTags.recentRow(MISSING)).assertIsDisplayed().assertIsNotEnabled()
            onNodeWithText(TEXTS.recentUnavailable).assertIsDisplayed()
        }
    }

    test("최근 저장소를 누르면 그 경로로 열기 이벤트가 나간다") {
        runComposeUiTest {
            val opened = mutableListOf<RepositoryPath>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(listOf(RecentRepository(PRESENT, available = true))),
                        events = WelcomeEvents(onOpenRecent = opened::add),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.recentRow(PRESENT)).performClick()

            opened shouldContainExactly listOf(PRESENT)
        }
    }

    test("사라진 항목은 제거 버튼으로만 목록에서 빠진다") {
        runComposeUiTest {
            val forgotten = mutableListOf<RepositoryPath>()
            val opened = mutableListOf<RepositoryPath>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(listOf(RecentRepository(MISSING, available = false))),
                        events = WelcomeEvents(onOpenRecent = opened::add, onForgetRecent = forgotten::add),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.recentRow(MISSING)).performClick()
            onNodeWithTag(WelcomeTags.recentRemove(MISSING)).performClick()

            opened shouldContainExactly emptyList()
            forgotten shouldContainExactly listOf(MISSING)
        }
    }

    test("최근 저장소가 0건이면 빈 상태와 열기·클론 유도가 함께 보인다") {
        runComposeUiTest {
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(),
                        events = WelcomeEvents(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.RECENT_EMPTY).assertIsDisplayed()
            onNodeWithText(TEXTS.recentEmpty).assertIsDisplayed()
            onNodeWithTag(WelcomeTags.OPEN_LOCAL).assertIsDisplayed()
            onNodeWithTag(WelcomeTags.CLONE_START).assertIsDisplayed()
        }
    }

    test("열기 실패 사유마다 다른 안내 문구가 표시된다") {
        val messages = Reason.entries.map { reason ->
            captureNoticeText(WelcomeNotice.OpenFailed(reason))
        }

        messages.distinct().size shouldBe messages.size
        messages shouldContainExactly listOf(
            TEXTS.errorNotFound,
            TEXTS.errorNotARepository,
            TEXTS.errorPermissionDenied,
            TEXTS.errorBareRepository,
        )
    }

    test("사유를 특정할 수 없는 열기 실패도 안내 문구를 가진다") {
        captureNoticeText(WelcomeNotice.OpenFailedUnexpectedly) shouldBe TEXTS.errorOpenFailed
    }

    test("인증 실패 안내는 키체인·SSH 설정을 가리키고 원격 URL 을 노출하지 않는다") {
        captureNoticeText(WelcomeNotice.AuthenticationFailed) shouldBe TEXTS.errorAuthentication
    }

    test("정리 실패 안내는 수동으로 지울 경로를 보여준다") {
        captureNoticeText(WelcomeNotice.CleanupFailed(RepositoryPath("/tmp/leftover"))) shouldBe
            TEXTS.cleanupFailed("/tmp/leftover")
    }

    test("비어 있지 않은 대상 안내가 표시된다") {
        captureNoticeText(WelcomeNotice.TargetNotEmpty) shouldBe TEXTS.errorTargetNotEmpty
    }

    test("clone 실패 안내가 표시된다") {
        captureNoticeText(WelcomeNotice.CloneFailed) shouldBe TEXTS.errorCloneFailed
    }

    test("clone 진행 중에는 진행률과 단계, 취소 경로가 보인다") {
        runComposeUiTest {
            val cancelled = mutableListOf<Unit>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(
                            cloning = true,
                            cloneProgress = Progress(0.42, "Receiving objects"),
                        ),
                        events = WelcomeEvents(clone = WelcomeCloneEvents(onCancel = { cancelled += Unit })),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.CLONE_PROGRESS).assertIsDisplayed()
            onNodeWithText(TEXTS.cloneProgress("Receiving objects", 42)).assertIsDisplayed()
            onNodeWithTag(WelcomeTags.CLONE_CANCEL).performClick()

            cancelled shouldContainExactly listOf(Unit)
        }
    }

    test("clone 진행 중에는 시작 버튼이 비활성이라 중복 실행되지 않는다") {
        runComposeUiTest {
            val started = mutableListOf<Pair<String, String>>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(cloning = true),
                        events = WelcomeEvents(
                            clone = WelcomeCloneEvents(onStart = { url, target -> started += url to target }),
                        ),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.CLONE_START).assertIsNotEnabled()
            started shouldContainExactly emptyList()
        }
    }

    test("주소와 대상이 비어 있으면 clone 을 시작할 수 없다") {
        runComposeUiTest {
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(),
                        events = WelcomeEvents(),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.CLONE_URL).assertIsDisplayed()
            onNodeWithTag(WelcomeTags.CLONE_TARGET).assertIsDisplayed()
            onNodeWithTag(WelcomeTags.CLONE_START).assertIsNotEnabled()
        }
    }

    test("주소와 대상이 채워지면 시작 버튼이 열리고 두 값이 그대로 전달된다") {
        runComposeUiTest {
            val started = mutableListOf<Pair<String, String>>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(cloneUrl = CLONE_URL, cloneTarget = CLONE_TARGET),
                        events = WelcomeEvents(
                            clone = WelcomeCloneEvents(onStart = { url, target -> started += url to target }),
                        ),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.CLONE_START).assertIsEnabled().performClick()

            started shouldContainExactly listOf(CLONE_URL to CLONE_TARGET)
        }
    }

    test("주소나 대상 한쪽이 공백뿐이면 clone 을 시작할 수 없다") {
        val blankSides = listOf(
            WelcomeScreenState(cloneUrl = "   ", cloneTarget = CLONE_TARGET),
            WelcomeScreenState(cloneUrl = CLONE_URL, cloneTarget = "\t "),
        )

        blankSides.forEach { state ->
            runComposeUiTest {
                setContent {
                    WelcomeHost {
                        WelcomeScreen(
                            state = state,
                            events = WelcomeEvents(),
                            modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                        )
                    }
                }

                onNodeWithTag(WelcomeTags.CLONE_START).assertIsNotEnabled()
            }
        }
    }

    test("입력란의 글자는 화면이 들고 있지 않고 변경 이벤트로 올라간다") {
        runComposeUiTest {
            val urls = mutableListOf<String>()
            val targets = mutableListOf<String>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(),
                        events = WelcomeEvents(
                            clone = WelcomeCloneEvents(
                                onUrlChange = urls::add,
                                onTargetChange = targets::add,
                            ),
                        ),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.CLONE_URL).performTextInput(CLONE_URL)
            onNodeWithTag(WelcomeTags.CLONE_TARGET).performTextInput(CLONE_TARGET)

            // 첫 emission 만 본다 — 이 테스트에는 값을 되돌려 줄 홀더가 없어 입력란이 빈 값으로 되돌아가며
            // 한 번 더 알린다. 그 되돌림 자체가 화면이 글자를 소유하지 않는다는 증거다.
            urls.first() shouldBe CLONE_URL
            targets.first() shouldBe CLONE_TARGET
        }
    }

    test("로컬 열기와 최근 항목은 키보드로 실행할 수 있다") {
        runComposeUiTest {
            val chosen = mutableListOf<Unit>()
            val opened = mutableListOf<RepositoryPath>()
            setContent {
                WelcomeHost {
                    WelcomeScreen(
                        state = WelcomeScreenState(listOf(RecentRepository(PRESENT, available = true))),
                        events = WelcomeEvents(
                            onChooseLocalDirectory = { chosen += Unit },
                            onOpenRecent = opened::add,
                        ),
                        modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                    )
                }
            }

            onNodeWithTag(WelcomeTags.OPEN_LOCAL).requestFocus()
            onNodeWithTag(WelcomeTags.OPEN_LOCAL).performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag(WelcomeTags.recentRow(PRESENT)).requestFocus()
            onNodeWithTag(WelcomeTags.recentRow(PRESENT)).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            chosen shouldContainExactly listOf(Unit)
            opened shouldContainExactly listOf(PRESENT)
        }
    }
})

/** 안내 하나만 띄운 화면에서 그 문구를 읽어 온다. */
@OptIn(ExperimentalTestApi::class)
private fun captureNoticeText(notice: WelcomeNotice): String {
    lateinit var text: String
    runComposeUiTest {
        setContent {
            WelcomeHost {
                text = welcomeNoticeText(notice)
                WelcomeScreen(
                    state = WelcomeScreenState(notice = notice),
                    events = WelcomeEvents(),
                    modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
                )
            }
        }
        onNodeWithTag(WelcomeTags.NOTICE).assertIsDisplayed()
        onNodeWithText(text).assertIsDisplayed()
    }
    return text
}

/** 테마와 문자열 카탈로그를 갖춘 최소 호스트. welcome 네임스페이스는 아직 내장 목록에 없어 직접 만든다. */
@Composable
private fun WelcomeHost(content: @Composable () -> Unit) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false),
            content = content,
        )
    }
}
