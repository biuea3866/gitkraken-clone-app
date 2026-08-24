package dev.undine.presentation.toolbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.domain.PushResult
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.toolbarTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred

/** 툴바 화면 — 버튼·진행 표시·취소·결과 안내·force push 확인·ahead/behind 배지. */
@OptIn(ExperimentalTestApi::class)
class RemoteToolbarSpec : FunSpec({

    test("fetch·pull·push 버튼을 누르면 각 원격 작업이 시작된다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            val state = toolbarStateWith(gateway)
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.FETCH).performClick()
            onNodeWithTag(ToolbarTags.PULL).performClick()
            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()

            gateway.fetchCalls shouldBe 1
            gateway.pullCalls shouldBe 1
            gateway.pushCalls shouldBe 1
            gateway.lastPushForce shouldBe false
        }
    }

    test("push 진행 중에는 진행 표시와 단계명이 뜨고 같은 버튼을 다시 눌러도 시작되지 않는다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway(CompletableDeferred())
            val state = toolbarStateWith(gateway)
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()
            requireNotNull(gateway.lastProgressCallback).invoke(
                dev.undine.domain.Progress(0.5, "Writing objects"),
            )
            waitForIdle()

            onNodeWithTag(ToolbarTags.PROGRESS).assertIsDisplayed()
            onNodeWithTag(ToolbarTags.PHASE).assertTextContains("Writing objects", substring = true)

            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()

            gateway.pushCalls shouldBe 1
        }
    }

    test("취소 버튼을 누르면 진행 표시가 사라지고 취소 안내가 뜬다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway(CompletableDeferred())
            val state = toolbarStateWith(gateway)
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.FETCH).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.CANCEL).performClick()
            waitForIdle()

            state.runningOperation.shouldBeNull()
            state.outcome shouldBe RemoteOperationOutcome.Cancelled(RemoteOperation.FETCH)
            onNodeWithTag(ToolbarTags.PROGRESS).assertDoesNotExist()
            onNodeWithTag(ToolbarTags.MESSAGE).assertIsDisplayed()
        }
    }

    test("취소를 요청한 뒤 명령이 끝나기 전에는 취소 중 표시가 남는다") {
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val gateway = FakeRemoteGateway(gate, ignoreCancellation = true)
            val state = toolbarStateWith(gateway)
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.CANCEL).performClick()
            waitForIdle()

            onNodeWithTag(ToolbarTags.CANCELLING).assertIsDisplayed()
            onNodeWithTag(ToolbarTags.CANCEL).assertDoesNotExist()
            onNodeWithTag(ToolbarTags.PROGRESS).assertIsDisplayed()

            gate.complete(Unit)
            waitForIdle()

            onNodeWithTag(ToolbarTags.CANCELLING).assertDoesNotExist()
            onNodeWithTag(ToolbarTags.MESSAGE).assertIsDisplayed()
        }
    }

    test("fetch 가 끝나면 갱신된 원격 참조 수가 결과로 보인다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            gateway.fetchResult = listOf(
                remoteRef("refs/remotes/origin/main"),
                remoteRef("refs/remotes/origin/dev"),
                remoteRef("refs/remotes/origin/release"),
            )
            setContent { ToolbarUnderTest(toolbarStateWith(gateway)) }

            onNodeWithTag(ToolbarTags.FETCH).performClick()
            waitForIdle()

            onNodeWithTag(ToolbarTags.MESSAGE).assertTextContains("3", substring = true)
        }
    }

    test("non-fast-forward 거절은 pull 후 재시도 안내로 보인다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            gateway.pushResult = PushResult.Rejected(PushResult.RejectReason.NON_FAST_FORWARD)
            setContent { ToolbarUnderTest(toolbarStateWith(gateway)) }

            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()

            onNodeWithTag(ToolbarTags.MESSAGE).assertTextContains("pull", substring = true)
        }
    }

    test("인증 실패 안내에 자격증명·원격 URL 이 노출되지 않는다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            gateway.failure = UndineException.AuthenticationFailed(REMOTE)
            setContent { ToolbarUnderTest(toolbarStateWith(gateway)) }

            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()

            onNodeWithTag(ToolbarTags.MESSAGE).assertTextContains("자격증명", substring = true)
        }
    }

    test("force push 는 기본 버튼에 없고 메뉴를 열어 경고를 확인해야 시작된다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            setContent { ToolbarUnderTest(toolbarStateWith(gateway)) }

            onNodeWithTag(ToolbarTags.FORCE_PUSH).assertDoesNotExist()

            onNodeWithTag(ToolbarTags.MORE_ACTIONS).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH).performClick()
            waitForIdle()

            // 확인 전에는 아무것도 보내지 않는다.
            gateway.pushCalls shouldBe 0
            onNodeWithTag(ToolbarTags.FORCE_PUSH_WARNING).assertTextContains("main", substring = true)

            onNodeWithTag(ToolbarTags.FORCE_PUSH_CONFIRM).performClick()
            waitForIdle()

            gateway.pushCalls shouldBe 1
            gateway.lastPushForce shouldBe true
        }
    }

    test("force push 확인을 취소하면 아무것도 보내지 않는다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            setContent { ToolbarUnderTest(toolbarStateWith(gateway)) }

            onNodeWithTag(ToolbarTags.MORE_ACTIONS).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH_DISMISS).performClick()
            waitForIdle()

            gateway.pushCalls shouldBe 0
            onNodeWithTag(ToolbarTags.FORCE_PUSH_WARNING).assertDoesNotExist()
        }
    }

    test("현재 브랜치의 ahead·behind 가 버튼 옆 배지로 보인다") {
        runComposeUiTest {
            val state = toolbarStateWith(FakeRemoteGateway(), branch = branchWith(ahead = 2, behind = 7))
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.AHEAD_BEHIND).assertTextContains("2", substring = true)
            onNodeWithTag(ToolbarTags.AHEAD_BEHIND).assertTextContains("7", substring = true)
        }
    }

    test("원격이 없으면 원격 버튼이 눌리지 않고 사유가 보인다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway()
            setContent { ToolbarUnderTest(toolbarStateWith(gateway, remotes = emptyList())) }

            onNodeWithTag(ToolbarTags.FETCH).performClick()
            onNodeWithTag(ToolbarTags.PUSH).performClick()
            waitForIdle()

            gateway.fetchCalls shouldBe 0
            gateway.pushCalls shouldBe 0
            onNodeWithTag(ToolbarTags.NOTICE).assertIsDisplayed()
            onNodeWithTag(ToolbarTags.MORE_ACTIONS).performClick()
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH).assertDoesNotExist()
        }
    }

    test("fetch·취소·force push 확인은 키보드만으로도 실행된다") {
        runComposeUiTest {
            val gateway = FakeRemoteGateway(CompletableDeferred())
            val state = toolbarStateWith(gateway)
            setContent { ToolbarUnderTest(state) }

            onNodeWithTag(ToolbarTags.FETCH).requestFocus()
            onNodeWithTag(ToolbarTags.FETCH).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            gateway.fetchCalls shouldBe 1

            onNodeWithTag(ToolbarTags.CANCEL).requestFocus()
            onNodeWithTag(ToolbarTags.CANCEL).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            state.outcome shouldBe RemoteOperationOutcome.Cancelled(RemoteOperation.FETCH)

            gateway.gate = null
            onNodeWithTag(ToolbarTags.MORE_ACTIONS).requestFocus()
            onNodeWithTag(ToolbarTags.MORE_ACTIONS).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH).requestFocus()
            onNodeWithTag(ToolbarTags.FORCE_PUSH).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNodeWithTag(ToolbarTags.FORCE_PUSH_CONFIRM).requestFocus()
            onNodeWithTag(ToolbarTags.FORCE_PUSH_CONFIRM).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            gateway.lastPushForce shouldBe true
        }
    }
})

/** 테마와 문자열 카탈로그를 갖춘 최소 호스트. toolbar 네임스페이스는 아직 내장 목록에 없어 직접 만든다. */
@Composable
private fun ToolbarUnderTest(state: RemoteToolbarState) {
    val catalog = StringCatalog(
        translations = mergeTranslations(listOf(commonTranslations, toolbarTranslations)),
        defaultLocale = DEFAULT_LOCALE,
    )
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false)) {
            RemoteToolbar(state = state, modifier = Modifier)
        }
    }
}
