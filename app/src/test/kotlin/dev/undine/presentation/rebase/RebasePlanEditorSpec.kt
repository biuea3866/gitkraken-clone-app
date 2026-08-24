package dev.undine.presentation.rebase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.domain.ThemeMode
import dev.undine.domain.rebase.InteractiveRebaseOutcome
import dev.undine.domain.rebase.RebaseAction
import dev.undine.domain.rebase.RebaseRunProgress
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
import dev.undine.presentation.i18n.mergeTranslations
import dev.undine.presentation.i18n.rebaseTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * 계획 편집기 화면 — 재정렬·동작 지정·미리보기·검증 사유·경고·진행률·적용/취소.
 *
 * 드래그 자체는 [dropTargetIndex] 단위 테스트가 맡는다 — 마우스 이벤트를 흉내내는 검증은 렌더
 * 크기·타이밍에 흔들려 규칙을 지키는지 알려주지 못한다. 화면은 그 결과가 계획에 반영되는지를 본다.
 */
@OptIn(ExperimentalTestApi::class)
class RebasePlanEditorSpec : FunSpec({

    test("계획이 오래된 것부터 나오고 저장소 무변경 안내가 붙는다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫", "둘")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.rowMessage(0), useUnmergedTree = true)
                .assertTextContains("첫", substring = true)
            onNodeWithTag(RebaseTags.rowMessage(1), useUnmergedTree = true)
                .assertTextContains("둘", substring = true)
            onNodeWithTag(RebaseTags.HINT).assertIsDisplayed()
        }
    }

    test("아래로 옮기면 계획 순서가 바뀐다") {
        runComposeUiTest {
            val gateway = RecordingRebaseGateway(targetsOf("첫", "둘"))
            val state = loadedState(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.moveDown(0)).performClick()
            waitForIdle()

            state.plan?.steps?.map { it.commit.message } shouldContainExactly listOf("둘", "첫")
            // 재정렬은 저장소를 건드리지 않는다.
            gateway.appliedPlans.shouldBeEmpty()
        }
    }

    test("첫 줄은 위로 옮길 수 없고 마지막 줄은 아래로 옮길 수 없다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫", "둘")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.moveUp(0)).assertIsNotEnabled()
            onNodeWithTag(RebaseTags.moveDown(1)).assertIsNotEnabled()
        }
    }

    test("첫 줄에 합치기를 지정하면 사유가 뜨고 적용이 잠긴다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫", "둘")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(0, ACTION_SQUASH)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.VIOLATION).assertIsDisplayed()
            onNodeWithTag(RebaseTags.APPLY).assertIsNotEnabled()
        }
    }

    test("전부 버리면 적용이 잠긴다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫", "둘")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(0, ACTION_DROP)).performClick()
            onNodeWithTag(RebaseTags.action(1, ACTION_DROP)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.VIOLATION).assertIsDisplayed()
            onNodeWithTag(RebaseTags.APPLY).assertIsNotEnabled()
        }
    }

    test("합치기로 묶인 커밋이 미리보기에서 하나로 접힌다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("기반", "고침")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(1, ACTION_SQUASH)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.PREVIEW).assertIsDisplayed()
            // 미리보기 줄이 하나로 줄고 몇 개가 합쳐지는지 알려준다.
            onNodeWithTag(RebaseTags.previewNote(0), useUnmergedTree = true)
                .assertTextContains("1", substring = true)
        }
    }

    test("버린 커밋은 미리보기에서 버려짐으로 표시된다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫", "둘")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(1, ACTION_DROP)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.previewNote(1), useUnmergedTree = true)
                .assertTextContains("버려짐", substring = true)
        }
    }

    test("메시지 수정을 고르면 입력칸이 생기고 입력이 계획에 담긴다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(0, ACTION_REWORD)).performClick()
            waitForIdle()
            onNodeWithTag(RebaseTags.rewordField(0)).performTextInput("고친 메시지")
            waitForIdle()

            // 실행 중 다시 묻지 않으려면 메시지가 계획에 있어야 한다.
            state.plan?.steps?.first()?.action shouldBe RebaseAction.Reword("고친 메시지")
        }
    }

    test("이미 원격에 있는 커밋은 표시되고 다시 쓰면 이력 분기 경고가 뜬다") {
        runComposeUiTest {
            val state = loadedState(
                RecordingRebaseGateway(targetsOf("원격", "로컬", pushed = setOf("원격"))),
            )
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.pushedMark(0), useUnmergedTree = true).assertIsDisplayed()

            onNodeWithTag(RebaseTags.action(0, ACTION_DROP)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.PUSHED_WARNING).assertIsDisplayed()
        }
    }

    test("'멈추고 편집' 을 고르면 실행 중 멈춘다는 안내가 뜬다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf("첫")))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.action(0, ACTION_EDIT)).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.STOPS_WARNING).assertIsDisplayed()
        }
    }

    test("적용하면 계획이 저장소로 가고 결과가 화면에 남는다") {
        runComposeUiTest {
            val gateway = RecordingRebaseGateway(
                targets = targetsOf("첫", "둘"),
                outcome = InteractiveRebaseOutcome.Completed,
            )
            val state = loadedState(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.APPLY).performClick()
            waitForIdle()

            gateway.appliedPlans.size shouldBe 1
            onNodeWithTag(RebaseTags.OUTCOME).assertIsDisplayed()
        }
    }

    test("충돌로 멈추면 몇 번째 커밋까지 적용했는지 보여준다") {
        runComposeUiTest {
            val gateway = RecordingRebaseGateway(
                targets = targetsOf("첫", "둘"),
                outcome = InteractiveRebaseOutcome.Conflicted(listOf("shared.txt")),
                progress = RebaseRunProgress(applied = 1, total = 2),
            )
            val state = loadedState(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.APPLY).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.OUTCOME).assertTextContains("shared.txt", substring = true)
            onNodeWithTag(RebaseTags.PROGRESS).assertTextContains("1", substring = true)
        }
    }

    test("취소하면 계획이 사라지고 저장소는 그대로다") {
        runComposeUiTest {
            val gateway = RecordingRebaseGateway(targetsOf("첫", "둘"))
            val state = loadedState(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.DISCARD).performClick()
            waitForIdle()

            onNodeWithTag(RebaseTags.EMPTY).assertIsDisplayed()
            gateway.appliedPlans.shouldBeEmpty()
        }
    }

    test("리베이스할 커밋이 없으면 빈 상태를 안내한다") {
        runComposeUiTest {
            val state = loadedState(RecordingRebaseGateway(targetsOf()))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(RebaseTags.EMPTY).assertIsDisplayed()
        }
    }
})

/** 대상을 이미 읽은 상태. 화면 단정은 여기서 시작한다. */
private fun loadedState(gateway: RecordingRebaseGateway): RebasePlanState =
    rebaseStateWith(gateway).also(RebasePlanState::load)

@Composable
private fun EditorUnderTest(state: RebasePlanState) {
    val catalog = StringCatalog(
        translations = mergeTranslations(listOf(commonTranslations, rebaseTranslations)),
        defaultLocale = DEFAULT_LOCALE,
    )
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
        ) {
            RebasePlanEditor(state = state, modifier = Modifier)
        }
    }
}
