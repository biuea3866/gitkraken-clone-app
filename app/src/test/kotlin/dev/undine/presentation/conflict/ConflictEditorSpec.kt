package dev.undine.presentation.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.domain.ThemeMode
import dev.undine.domain.conflict.ConflictSide
import dev.undine.domain.conflict.ConflictedFile
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commonTranslations
import dev.undine.presentation.i18n.conflictTranslations
import dev.undine.presentation.i18n.mergeTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly as shouldContainExactlyEntries
import io.kotest.matchers.string.shouldContain

private const val TEXT_FILE = "shared.txt"
private const val OTHER_FILE = "notes.md"
private const val BINARY_FILE = "logo.png"

private val TWO_REGIONS = listOf(
    "머리말",
    "<<<<<<< HEAD",
    "우리 첫째",
    "=======",
    "저쪽 첫째",
    ">>>>>>> feature",
    "사이",
    "<<<<<<< HEAD",
    "우리 둘째",
    "=======",
    "저쪽 둘째",
    ">>>>>>> feature",
).joinToString("\n")

private val ONE_REGION_WITH_BASE = listOf(
    "<<<<<<< HEAD",
    "우리 줄",
    "||||||| merged common ancestors",
    "조상 줄",
    "=======",
    "저쪽 줄",
    ">>>>>>> feature",
).joinToString("\n")

/**
 * 충돌 해결 에디터 화면 — 세 원본 동시 표시·구간별 채택·표식 잔존 차단·진행률·이진 선택·
 * 파일별 해결 표시·중단 2단계 확인.
 */
@OptIn(ExperimentalTestApi::class)
class ConflictEditorSpec : FunSpec({

    test("충돌 파일의 ours·base·theirs 세 버전이 함께 표시된다") {
        runComposeUiTest {
            val state = loadedState(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.OURS_PANE).assertTextContains("우리 줄", substring = true)
            onNodeWithTag(ConflictTags.BASE_PANE).assertTextContains("조상 줄", substring = true)
            onNodeWithTag(ConflictTags.THEIRS_PANE).assertTextContains("저쪽 줄", substring = true)
        }
    }

    test("구간별로 ours 를 채택하면 결과에 그 내용이 들어간다") {
        runComposeUiTest {
            val state = loadedState(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.TAKE_OURS).performClick()
            waitForIdle()

            onNodeWithTag(ConflictTags.RESULT_EDITOR).assertTextContains("우리 줄", substring = true)
        }
    }

    test("'둘 다' 를 고르면 양쪽 내용이 순서대로 결과에 들어간다") {
        runComposeUiTest {
            val gateway = textGateway(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE))
            val state = loadedState(gateway = gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.TAKE_BOTH).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.SAVE).performClick()
            waitForIdle()

            gateway.resolvedContents.getValue(TEXT_FILE) shouldContain "우리 줄\n저쪽 줄"
        }
    }

    test("표식이 남은 채로 저장하면 차단되고 남은 줄 번호가 표시된다") {
        runComposeUiTest {
            val gateway = textGateway(mapOf(TEXT_FILE to TWO_REGIONS))
            val state = loadedState(gateway = gateway)
            setContent { EditorUnderTest(state) }

            // 첫 구간만 고르고 저장한다 — 둘째 구간의 표식이 그대로 남는다.
            onNodeWithTag(ConflictTags.TAKE_OURS).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.SAVE).performClick()
            waitForIdle()

            onNodeWithTag(ConflictTags.MARKERS_REMAIN).assertIsDisplayed()
            gateway.resolvedContents.keys.shouldBeEmpty()
        }
    }

    test("해결 진행률이 남은 구간 수와 함께 표시된다") {
        runComposeUiTest {
            val state = loadedState(mapOf(TEXT_FILE to TWO_REGIONS))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.PROGRESS).assertTextContains("2", substring = true)

            onNodeWithTag(ConflictTags.TAKE_THEIRS).performClick()
            waitForIdle()

            onNodeWithTag(ConflictTags.PROGRESS).assertTextContains("1", substring = true)
        }
    }

    test("이진 파일 충돌은 ours·theirs 선택만 제공한다") {
        runComposeUiTest {
            val gateway = RecordingConflictGateway(
                files = listOf(ConflictedFile(BINARY_FILE, isBinary = true)),
                contents = emptyMap(),
            )
            val state = conflictStateWith(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.BINARY_NOTICE).assertIsDisplayed()
            onNodeWithTag(ConflictTags.TAKE_BOTH).assertDoesNotExist()
            onNodeWithTag(ConflictTags.RESULT_EDITOR).assertDoesNotExist()

            onNodeWithTag(ConflictTags.TAKE_THEIRS).performClick()
            waitForIdle()

            gateway.binaryChoices shouldContainExactlyEntries mapOf(BINARY_FILE to ConflictSide.THEIRS)
        }
    }

    test("파일이 여러 개면 해결한 파일에 표시가 붙는다") {
        runComposeUiTest {
            val gateway = RecordingConflictGateway(
                files = listOf(
                    ConflictedFile(TEXT_FILE, isBinary = false),
                    ConflictedFile(OTHER_FILE, isBinary = false),
                ),
                contents = mapOf(
                    TEXT_FILE to ONE_REGION_WITH_BASE,
                    OTHER_FILE to ONE_REGION_WITH_BASE,
                ),
            )
            val state = conflictStateWith(gateway)
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.fileRow(OTHER_FILE)).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.TAKE_OURS).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.SAVE).performClick()
            waitForIdle()

            // 해결한 파일은 목록에서 빠지고 남은 파일만 보인다.
            onNodeWithTag(ConflictTags.fileRow(OTHER_FILE)).assertDoesNotExist()
            onNodeWithTag(ConflictTags.fileRow(TEXT_FILE)).assertIsDisplayed()
            gateway.resolvedContents.keys shouldContainExactly listOf(OTHER_FILE)
        }
    }

    test("중단으로 되돌릴 수 있음을 화면에 안내한다") {
        runComposeUiTest {
            val state = loadedState(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.ABORT_NOTICE).assertIsDisplayed()
        }
    }

    test("사라질 경로와 복구 불가성을 보여 준 확인 뒤에만 중단이 실행된다") {
        runComposeUiTest {
            val gateway = textGateway(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE))
            val merge = StubMergeGateway()
            val state = conflictStateWith(
                conflict = gateway,
                repository = FixedStatusRepositoryGateway(
                    unstaged = listOf("edited.txt"),
                    conflicted = listOf(TEXT_FILE),
                ),
                merge = merge,
            )
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.ABORT).performClick()
            waitForIdle()

            // 확인 대화가 뜬 것만으로는 실행되지 않는다.
            onNodeWithTag(ConflictTags.ABORT_DIALOG).assertIsDisplayed()
            onNodeWithTag(ConflictTags.ABORT_PATHS).assertTextContains("edited.txt", substring = true)
            merge.calls.shouldBeEmpty()

            onNodeWithTag(ConflictTags.ABORT_ACCEPT).performClick()
            waitForIdle()

            merge.calls shouldContainExactly listOf("abortMerge")
        }
    }

    test("중단 확인을 취소하면 아무것도 되돌리지 않는다") {
        runComposeUiTest {
            val merge = StubMergeGateway()
            val state = conflictStateWith(
                conflict = textGateway(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE)),
                merge = merge,
            )
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.ABORT).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.ABORT_CANCEL).performClick()
            waitForIdle()

            onNodeWithTag(ConflictTags.ABORT_DIALOG).assertDoesNotExist()
            merge.calls.shouldBeEmpty()
        }
    }

    // 대화상자를 마우스로만 닫을 수 있으면 키보드만 쓰는 사용자는 갇힌다 — 확인 버튼을 찾아
    // 누르는 것 말고 빠져나올 길이 없다 (UND-50 접근성 감사).
    test("중단 확인은 ESC 로 닫히고 되돌리지 않는다") {
        runComposeUiTest {
            val merge = StubMergeGateway()
            val state = conflictStateWith(
                conflict = textGateway(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE)),
                merge = merge,
            )
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.ABORT).performClick()
            waitForIdle()
            onNodeWithTag(ConflictTags.ABORT_DIALOG).assertIsDisplayed()

            onNodeWithTag(ConflictTags.ABORT_CANCEL).requestFocus()
            onNodeWithTag(ConflictTags.ABORT_CANCEL).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(ConflictTags.ABORT_DIALOG).assertDoesNotExist()
            merge.calls.shouldBeEmpty()
        }
    }

    test("중단 확인이 열려 있는 동안 포커스는 대화상자 밖으로 나가지 않는다") {
        runComposeUiTest {
            val state = conflictStateWith(
                conflict = textGateway(mapOf(TEXT_FILE to ONE_REGION_WITH_BASE)),
                merge = StubMergeGateway(),
            )
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.ABORT).performClick()
            waitForIdle()

            // 뒤에 가린 편집 화면의 조작으로 Tab 이 새면 사용자는 확인하지 않은 채 다른 것을 만진다.
            onNodeWithTag(ConflictTags.ABORT_ACCEPT).requestFocus()
            onNodeWithTag(ConflictTags.ABORT_ACCEPT).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag(ConflictTags.ABORT).assertIsNotFocused()
        }
    }

    test("충돌이 없으면 빈 상태를 안내한다") {
        runComposeUiTest {
            val state = conflictStateWith(RecordingConflictGateway(emptyList(), emptyMap()))
            setContent { EditorUnderTest(state) }

            onNodeWithTag(ConflictTags.EMPTY).assertIsDisplayed()
            onNodeWithTag(ConflictTags.ROOT).assertDoesNotExist()
        }
    }
})

private fun textGateway(contents: Map<String, String>) = RecordingConflictGateway(
    files = contents.keys.map { path -> ConflictedFile(path, isBinary = false) },
    contents = contents,
)

/** 목록·문서를 이미 읽은 상태. 화면 단정은 여기서 시작한다. */
private fun loadedState(
    contents: Map<String, String> = emptyMap(),
    gateway: RecordingConflictGateway = textGateway(contents),
): ConflictState = conflictStateWith(gateway)

/**
 * 테스트용 배선. 목록을 읽고 첫 파일을 고른 상태에서 그린다 — 고르기를 목록 로딩과 한 효과에 두면
 * `select` 가 목록보다 먼저 실행돼 문서가 비어 있다.
 */
@Composable
private fun EditorUnderTest(state: ConflictState) {
    val catalog = StringCatalog(
        translations = mergeTranslations(listOf(commonTranslations, conflictTranslations)),
        defaultLocale = DEFAULT_LOCALE,
    )
    androidx.compose.runtime.LaunchedEffect(Unit) { state.refresh() }
    androidx.compose.runtime.LaunchedEffect(state.files) {
        if (state.selectedPath == null) state.files.firstOrNull()?.let { state.select(it.path) }
    }
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
        ) {
            ConflictEditor(state = state, modifier = Modifier)
        }
    }
}
