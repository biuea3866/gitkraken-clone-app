package dev.undine.presentation.commitdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import dev.undine.domain.ChangeType
import dev.undine.domain.Commit
import dev.undine.domain.CommitId
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.commitDetail
import dev.undine.presentation.i18n.commitDetailTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val PANEL_WIDTH = 520.dp
private val PANEL_HEIGHT = 640.dp

private val LOCALIZED = StringCatalog(translations = commitDetailTranslations, defaultLocale = DEFAULT_LOCALE)
    .stringsFor(DEFAULT_LOCALE, devBuild = false)
    .commitDetail

/** 클립보드 대역 — 복사가 실제로 일어났는지 값으로 확인한다. */
private class RecordingClipboardManager : ClipboardManager {
    var copied: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        copied = annotatedString
    }

    override fun getText(): AnnotatedString? = copied
}

/** 커밋 상세 패널 — 메타 렌더링·기준 부모 전환·파일 선택·해시 복사·빈/실패 상태. */
@OptIn(ExperimentalTestApi::class)
class CommitDetailPanelSpec : FunSpec({

    test("선택한 커밋의 전체 해시·작성자·메시지 제목이 표시된다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(message = "제목 줄\n\n본문 줄")) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.HASH).assertTextContains(TARGET_COMMIT.value)
            onNodeWithTag(CommitDetailTags.AUTHOR).assertTextContains(AUTHOR.name, substring = true)
            onNodeWithTag(CommitDetailTags.AUTHOR).assertTextContains(AUTHOR.email, substring = true)
            onNodeWithTag(CommitDetailTags.MESSAGE_SUBJECT).assertTextContains("제목 줄")
        }
    }

    test("작성자와 커미터가 다르면 두 정보가 모두 표시된다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(author = AUTHOR, committer = COMMITTER)) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.AUTHOR).assertTextContains(AUTHOR.name, substring = true)
            onNodeWithTag(CommitDetailTags.COMMITTER).assertTextContains(COMMITTER.name, substring = true)
        }
    }

    test("작성자와 커미터가 같으면 커미터 줄을 따로 그리지 않는다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(author = AUTHOR, committer = AUTHOR)) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.AUTHOR).assertIsDisplayed()
            onNodeWithTag(CommitDetailTags.COMMITTER).assertDoesNotExist()
        }
    }

    test("본문이 있는 메시지는 접혀 있다가 펼치면 본문이 보인다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(message = "제목\n\n본문 내용")) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.MESSAGE_BODY).assertDoesNotExist()

            onNodeWithTag(CommitDetailTags.MESSAGE_TOGGLE).performClick()
            waitForIdle()

            onNodeWithTag(CommitDetailTags.MESSAGE_BODY).assertTextContains("본문 내용")
        }
    }

    test("본문이 없는 메시지에는 접기 조작이 없다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(message = "제목만 있음")) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.MESSAGE_TOGGLE).assertDoesNotExist()
        }
    }

    test("변경 파일 목록이 경로·변경 종류·증감 줄 수와 함께 표시된다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    FIRST_PARENT_INDEX to listOf(
                        fileChangeOf("src/App.kt", ChangeType.MODIFIED, addedLines = 7, deletedLines = 2),
                    ),
                ),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            val row = onNodeWithTag(CommitDetailTags.fileRow("src/App.kt"))
            row.assertTextContains("src/App.kt", substring = true)
            row.assertTextContains(LOCALIZED.changeType(ChangeType.MODIFIED), substring = true)
            row.assertTextContains(LOCALIZED.lineStats(added = 7, deleted = 2), substring = true)
        }
    }

    test("rename·copy 는 이전 경로를 함께 보여 준다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    FIRST_PARENT_INDEX to listOf(
                        fileChangeOf("src/New.kt", ChangeType.RENAMED, previousPath = "src/Old.kt"),
                        fileChangeOf("src/Copy.kt", ChangeType.COPIED, previousPath = "src/Source.kt"),
                    ),
                ),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            // 새 경로만 보이면 "어디서 왔는지" 를 읽을 수 없다 — 두 종류 모두 이전 경로를 함께 낸다.
            val renamed = onNodeWithTag(CommitDetailTags.fileRow("src/New.kt"))
            renamed.assertTextContains(LOCALIZED.changeType(ChangeType.RENAMED), substring = true)
            renamed.assertTextContains(LOCALIZED.renamedFrom("src/Old.kt"), substring = true)

            val copied = onNodeWithTag(CommitDetailTags.fileRow("src/Copy.kt"))
            copied.assertTextContains(LOCALIZED.changeType(ChangeType.COPIED), substring = true)
            copied.assertTextContains(LOCALIZED.renamedFrom("src/Source.kt"), substring = true)
        }
    }

    test("이전 경로가 없는 변경에는 이전 경로 문구를 붙이지 않는다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    FIRST_PARENT_INDEX to listOf(fileChangeOf("src/App.kt", ChangeType.MODIFIED)),
                ),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            // 이전 경로 문구의 고정 부분("이전 경로")이 행 어디에도 없어야 한다.
            onNodeWithTag(CommitDetailTags.fileRow("src/App.kt"))
                .rowText() shouldNotContain renamedFromPrefix()
        }
    }

    test("binary 파일은 증감 줄 수 대신 binary 문구를 보여 준다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    FIRST_PARENT_INDEX to listOf(
                        binaryFileChangeOf("assets/logo.png"),
                    ),
                ),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            // 이진 파일에 "+0 -0" 을 내면 변경이 없는 것처럼 읽힌다.
            val row = onNodeWithTag(CommitDetailTags.fileRow("assets/logo.png"))
            row.assertTextContains(LOCALIZED.binary, substring = true)
            row.rowText() shouldNotContain LOCALIZED.lineStats(added = 0, deleted = 0)
        }
    }

    test("대형 커밋의 파일 목록을 그려도 hunk 는 요청되지 않는다") {
        runComposeUiTest {
            val files = (1..300).map { fileChangeOf("src/File$it.kt") }
            val gateway = FakeDiffGateway(filesByParentIndex = mapOf(FIRST_PARENT_INDEX to files))
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            // FakeDiffGateway.hunksOf 는 호출되면 실패한다 — 여기까지 왔다면 요청이 없었다는 뜻이다.
            onNodeWithTag(CommitDetailTags.fileRow("src/File1.kt")).assertIsDisplayed()
            gateway.requestedParentIndexes shouldContainExactly listOf(FIRST_PARENT_INDEX)
        }
    }

    test("파일을 선택하면 선택 상태가 갱신된다") {
        runComposeUiTest {
            val selected = mutableListOf<String>()
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(FIRST_PARENT_INDEX to listOf(fileChangeOf("src/App.kt"))),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway, onSelectFile = selected::add) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.fileRow("src/App.kt")).performClick()
            waitForIdle()

            selected shouldContainExactly listOf("src/App.kt")
        }
    }

    test("파일 행은 키보드로도 선택된다") {
        runComposeUiTest {
            val selected = mutableListOf<String>()
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(FIRST_PARENT_INDEX to listOf(fileChangeOf("src/App.kt"))),
            )
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway, onSelectFile = selected::add) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.fileRow("src/App.kt")).requestFocus()
            onNodeWithTag(CommitDetailTags.fileRow("src/App.kt")).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            selected shouldContainExactly listOf("src/App.kt")
        }
    }

    test("해시를 클릭하면 전체 해시가 클립보드에 복사된다") {
        runComposeUiTest {
            val clipboard = RecordingClipboardManager()
            setContent { PanelUnderTest(commit = commitOf(), clipboard = clipboard) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.HASH).performClick()
            waitForIdle()

            clipboard.copied?.text shouldBe TARGET_COMMIT.value
        }
    }

    test("해시는 키보드로도 복사된다") {
        runComposeUiTest {
            val clipboard = RecordingClipboardManager()
            setContent { PanelUnderTest(commit = commitOf(), clipboard = clipboard) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.HASH).requestFocus()
            onNodeWithTag(CommitDetailTags.HASH).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            clipboard.copied?.text shouldBe TARGET_COMMIT.value
        }
    }

    test("부모 커밋 링크를 누르면 그 커밋으로 이동을 요청한다") {
        runComposeUiTest {
            val navigated = mutableListOf<CommitId>()
            setContent { PanelUnderTest(commit = commitOf(), onSelectParentCommit = navigated::add) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.parentLink(0)).performClick()
            waitForIdle()

            navigated shouldContainExactly listOf(FIRST_PARENT)
        }
    }

    test("병합 커밋은 기준 부모를 고를 수 있고 기본값은 첫 부모다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    0 to listOf(fileChangeOf("from-first.kt")),
                    1 to listOf(fileChangeOf("from-second.kt")),
                ),
            )
            setContent {
                PanelUnderTest(
                    commit = commitOf(parents = listOf(FIRST_PARENT, SECOND_PARENT)),
                    gateway = gateway,
                )
            }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.BASE_PARENT_SELECTOR).assertIsDisplayed()
            onNodeWithTag(CommitDetailTags.fileRow("from-first.kt")).assertIsDisplayed()

            onNodeWithTag(CommitDetailTags.baseParentOption(1)).performClick()
            waitForIdle()

            onNodeWithTag(CommitDetailTags.fileRow("from-second.kt")).assertIsDisplayed()
            onNodeWithTag(CommitDetailTags.fileRow("from-first.kt")).assertDoesNotExist()
            gateway.requestedParentIndexes shouldContainExactly listOf(0, 1)
        }
    }

    test("부모가 하나인 커밋에는 기준 부모 선택이 없다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf()) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.BASE_PARENT_SELECTOR).assertDoesNotExist()
        }
    }

    test("최초 커밋은 부모 없음을 알리고 전체 파일을 추가로 표시한다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(
                filesByParentIndex = mapOf(
                    FIRST_PARENT_INDEX to listOf(
                        fileChangeOf("README.md", ChangeType.ADDED, deletedLines = 0),
                        fileChangeOf("build.gradle.kts", ChangeType.ADDED, deletedLines = 0),
                    ),
                ),
            )
            setContent { PanelUnderTest(commit = commitOf(parents = emptyList()), gateway = gateway) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.PARENTS).assertTextContains(LOCALIZED.noParents, substring = true)
            listOf("README.md", "build.gradle.kts").forEach { path ->
                onNodeWithTag(CommitDetailTags.fileRow(path))
                    .assertTextContains(LOCALIZED.changeType(ChangeType.ADDED), substring = true)
            }
        }
    }

    test("변경 파일이 없는 빈 커밋도 안내와 함께 정상 표시된다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(), gateway = FakeDiffGateway()) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.ROOT).assertIsDisplayed()
            onNodeWithTag(CommitDetailTags.FILE_EMPTY).assertTextContains(LOCALIZED.noChanges, substring = true)
        }
    }

    test("조회가 실패하면 빈 목록 대신 실패 안내를 보여준다") {
        runComposeUiTest {
            val gateway = FakeDiffGateway(failure = UndineException.GitOperationFailed("changedFiles"))
            setContent { PanelUnderTest(commit = commitOf(), gateway = gateway) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.FILE_FAILED).assertTextContains(LOCALIZED.loadFailed, substring = true)
            onNodeWithTag(CommitDetailTags.FILE_EMPTY).assertDoesNotExist()
        }
    }

    test("작성 시각과 커밋 시각이 각각 표시된다") {
        runComposeUiTest {
            setContent { PanelUnderTest(commit = commitOf(author = AUTHOR, committer = COMMITTER)) }
            waitForIdle()

            onNodeWithTag(CommitDetailTags.AUTHORED_AT).assertIsDisplayed()
            onNodeWithTag(CommitDetailTags.COMMITTED_AT).assertIsDisplayed()
        }
    }
})

@Composable
private fun PanelUnderTest(
    commit: Commit,
    gateway: FakeDiffGateway = FakeDiffGateway(),
    clipboard: ClipboardManager = RecordingClipboardManager(),
    onSelectFile: (String) -> Unit = {},
    onSelectParentCommit: (CommitId) -> Unit = {},
) {
    val catalog = remember { StringCatalog(commitDetailTranslations, DEFAULT_LOCALE) }
    // UseCase 를 매 재구성마다 새로 만들면 remember 키가 바뀌어 상태가 초기화된다.
    val useCase = remember(gateway) { useCaseOf(gateway) }
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides catalog.stringsFor(DEFAULT_LOCALE, devBuild = false),
            LocalClipboardManager provides clipboard,
        ) {
            CommitDetailPanel(
                commit = commit,
                state = rememberCommitDetailState(useCase),
                modifier = Modifier.size(PANEL_WIDTH, PANEL_HEIGHT),
                onSelectFile = onSelectFile,
                onSelectParentCommit = onSelectParentCommit,
            )
        }
    }
}

/** 행이 실제로 그린 텍스트를 한 덩어리로 모은다 — "무엇이 없다" 를 보려면 전체를 봐야 한다. */
private fun SemanticsNodeInteraction.rowText(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

/**
 * 이전 경로 문구에서 **경로를 뺀 고정 부분**. 자리표시자만 바꿔 얻으므로 문구가 번역돼도 따라간다.
 * 경로까지 포함해 비교하면 "다른 경로였다면 통과" 하는 약한 검증이 된다.
 */
private fun renamedFromPrefix(): String =
    LOCALIZED.renamedFrom("").trim()
