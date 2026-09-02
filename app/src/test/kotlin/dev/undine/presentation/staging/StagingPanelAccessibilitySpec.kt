package dev.undine.presentation.staging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.domain.ThemeMode
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.staging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull

private const val MESSAGE = "amend 확인을 접근성 기준으로 본다"
private val STRINGS = builtInStringCatalog().stringsFor(DEFAULT_LOCALE, devBuild = false)

/**
 * 스테이징 패널의 접근성 — 커밋 메시지 입력창의 이름과 amend 확인의 ESC·포커스 트랩.
 *
 * 커밋 메시지 입력창은 **빈 상태에서 읽을 텍스트가 없다** — 이름을 따로 붙이지 않으면 스크린리더가
 * "편집 상자" 라고만 읽는다. amend 확인은 창이 아니라 화면 안에 겹쳐 그리는 패널이라 ESC·포커스
 * 트랩이 공짜로 오지 않는다 (UND-50 접근성 감사).
 */
@OptIn(ExperimentalTestApi::class)
class StagingPanelAccessibilitySpec : FunSpec({

    test("커밋 메시지 입력창은 빈 상태에서도 스크린리더가 읽을 이름을 가진다") {
        runComposeUiTest {
            val state = stagingStateWith(
                FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))),
                RecordingStagingGateway(),
            )
            state.refresh()
            setContent { PanelUnderTest(state) }

            onNodeWithContentDescription(STRINGS.staging.messagePlaceholder).assertIsDisplayed()
        }
    }

    test("amend 확인은 ESC 로 닫히고 저장소를 바꾸지 않는다") {
        runComposeUiTest {
            val staging = RecordingStagingGateway(amendExistsOnRemote = true, amendTarget = commitId("f"))
            val state = stagingStateWith(
                FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))),
                staging,
            )
            state.refresh()
            state.changeMessage(MESSAGE)
            state.requestAmendMode(true)
            state.commit()
            setContent { PanelUnderTest(state) }

            onNodeWithTag(StagingTags.AMEND_DIALOG).assertIsDisplayed()

            onNodeWithTag(StagingTags.AMEND_CANCEL).requestFocus()
            onNodeWithTag(StagingTags.AMEND_CANCEL).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(StagingTags.AMEND_DIALOG).assertDoesNotExist()
            state.amendConfirmation.shouldBeNull()
            staging.amendMessages.shouldBeEmpty()
        }
    }

    test("amend 확인이 열려 있는 동안 포커스는 대화상자 밖으로 나가지 않는다") {
        runComposeUiTest {
            val staging = RecordingStagingGateway(amendExistsOnRemote = true, amendTarget = commitId("f"))
            val state = stagingStateWith(
                FakeRepositoryGateway(statusOf(staged = listOf("a.kt"))),
                staging,
            )
            state.refresh()
            state.changeMessage(MESSAGE)
            state.requestAmendMode(true)
            state.commit()
            setContent { PanelUnderTest(state) }

            onNodeWithTag(StagingTags.AMEND_ACCEPT).requestFocus()
            onNodeWithTag(StagingTags.AMEND_ACCEPT).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()

            // 확인 전에 뒤의 커밋 버튼으로 Tab 이 새면 사용자는 확인을 지나쳐 커밋한다.
            onNodeWithTag(StagingTags.COMMIT).assertIsNotFocused()
        }
    }
})

@Composable
private fun PanelUnderTest(state: StagingState) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalStrings provides STRINGS) {
            StagingPanel(state = state)
        }
    }
}
