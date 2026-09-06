package dev.undine.presentation.patch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.patch.MAX_PREVIEW_PATCH_BYTES
import dev.undine.domain.patch.ApplyOutcome
import dev.undine.domain.patch.CommitPatch
import dev.undine.domain.patch.PatchExport
import dev.undine.domain.patch.UnsupportedReason
import dev.undine.domain.UndineException
import dev.undine.presentation.diff.DiffTags
import dev.undine.presentation.diff.DiffViewMode
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.patch
import dev.undine.testsupport.commit
import dev.undine.testsupport.commitId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.CompositionLocalProvider

private val catalog = builtInStringCatalog()
private val copy = catalog.stringsFor(DEFAULT_LOCALE, devBuild = false).patch
private val englishCopy = catalog.stringsFor(Locale.ENGLISH, devBuild = false).patch

private val COMBINED_PATCH = """
    diff --git a/src/Main.kt b/src/Main.kt
    --- a/src/Main.kt
    +++ b/src/Main.kt
    @@ -1,1 +1,1 @@
    -old
    +new
""".trimIndent().toByteArray()

/** 미리보기 상한(1 MiB)을 한 바이트 넘긴 패치. 넘겼다는 사실만 필요하므로 내용은 채우지 않는다. */
private val OVERSIZED_PATCH = ByteArray(MAX_PREVIEW_PATCH_BYTES + 1) { ' '.code.toByte() }

private val BINARY_PATCH = """
    diff --git a/logo.png b/logo.png
    GIT binary patch
    literal 12
""".trimIndent().toByteArray()

/**
 * Patch 화면 — 생성 선택지·포함 내용, dry-run 결과와 적용 차단, 읽기 전용 미리보기, 두 로케일.
 *
 * 파일 대화상자는 [FakePatchFiles] 로 대체한다 — 실제 AWT 를 열면 테스트가 사람 조작을 기다린다.
 */
@OptIn(ExperimentalTestApi::class)
class PatchScreenSpec : FunSpec({

    test("생성 범위 셋과 두 생성 형식이 모두 화면에 있고 기본값은 전체 변경·워킹트리만이다") {
        runComposeUiTest {
            val state = state(FakePatchActions())
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.ROOT).assertIsDisplayed()
            onNodeWithTag(PatchTags.scope("ALL")).assertIsDisplayed()
            onNodeWithTag(PatchTags.scope("STAGED")).assertIsDisplayed()
            onNodeWithTag(PatchTags.scope("UNSTAGED")).assertIsDisplayed()
            onNodeWithTag(PatchTags.FORMAT_COMBINED).assertIsDisplayed()
            onNodeWithTag(PatchTags.FORMAT_PER_COMMIT).assertIsDisplayed()

            state.workingTreeScope.name shouldBe "ALL"
            state.applyMode shouldBe PatchApplyModeChoice.WORKING_TREE_ONLY
        }
    }

    test("생성 전에 포함될 파일 목록과 총 크기가 표시된다") {
        runComposeUiTest {
            val export = PatchExport(emptyList(), COMBINED_PATCH)
            val state = state(FakePatchActions(exportResult = export))
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.PREPARE).performClick()
            waitForIdle()

            onNodeWithTag(PatchTags.INCLUDED_FILES).assertIsDisplayed()
            onNodeWithText("src/Main.kt").assertIsDisplayed()
            onNodeWithText(copy.totalSize(COMBINED_PATCH.size)).assertIsDisplayed()
        }
    }

    test("커밋 선택이 비면 생성 버튼이 막히고 고르면 열린다") {
        runComposeUiTest {
            val state = state(FakePatchActions(commits = listOf(commit(2, 1), commit(1))))
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.SOURCE_COMMITS).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.PREPARE).assertIsNotEnabled()

            onNodeWithTag(PatchTags.commitRow(0)).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.PREPARE).assertIsEnabled()
        }
    }

    test("형식마다 저장 대상이 다르다 — 단일은 파일, 커밋당은 디렉터리") {
        runComposeUiTest {
            val export = PatchExport(listOf(CommitPatch(commitId(1), COMBINED_PATCH)), COMBINED_PATCH)
            val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), directoryChoice = path("/tmp/patches"))
            val state = state(FakePatchActions(exportResult = export), files)
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.PREPARE).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.SAVE).performClick()
            waitForIdle()

            files.saveDefaults shouldContainExactly listOf(COMBINED_PATCH_FILE_NAME)
            files.directoryRequests shouldBe 0

            onNodeWithTag(PatchTags.FORMAT_PER_COMMIT).performClick()
            onNodeWithTag(PatchTags.PREPARE).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.SAVE).performClick()
            waitForIdle()

            files.directoryRequests shouldBe 1
            files.written.map { it.first.toString() } shouldContainExactly listOf(
                "/tmp/out.patch",
                "/tmp/patches/0001.patch",
            )
        }
    }

    test("같은 이름이 이미 있으면 저장 전에 확인 문구와 두 선택지를 보여 준다") {
        runComposeUiTest {
            val files = FakePatchFiles(
                saveChoice = path("/tmp/out.patch"),
                existing = setOf(path("/tmp/out.patch")),
            )
            val state = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.PREPARE).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.SAVE).performClick()
            waitForIdle()

            onNodeWithText(copy.overwriteWarning("out.patch")).assertIsDisplayed()
            onNodeWithTag(PatchTags.OVERWRITE_CANCEL).performClick()
            waitForIdle()

            files.written.isEmpty() shouldBe true
        }
    }

    test("저장이 실패하고 임시 파일까지 남으면 어떤 파일이 남았는지 화면이 말한다") {
        runComposeUiTest {
            val leftover = path("/tmp/undine-patch1234.tmp")
            val failure = IOException("디스크 가득 참").apply {
                addSuppressed(PatchTemporaryFileLeftException(leftover, IOException("권한 없음")))
            }
            val files = FakePatchFiles(saveChoice = path("/tmp/out.patch"), writeFailure = failure)
            val state = state(FakePatchActions(exportResult = PatchExport(emptyList(), COMBINED_PATCH)), files)
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.PREPARE).performClick()
            waitForIdle()
            onNodeWithTag(PatchTags.SAVE).performClick()
            waitForIdle()

            onNodeWithTag(PatchTags.SAVE_TEMPORARY_LEFT).assertIsDisplayed()
            onNodeWithText(copy.saveTemporaryLeft(leftover.toString())).assertIsDisplayed()
        }
    }

    test("적용 전에 dry-run 결과가 화면에 표시되고 그 뒤에야 적용을 누를 수 있다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions())
            setContent { ScreenUnderTest(state) }

            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.changedFiles).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsEnabled()
        }
    }

    test("충돌·미지원 두 사유·경로 이탈은 각각의 사유를 보여 주고 적용을 막는다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions(dryRunOutcome = ApplyOutcome.Conflicted(listOf("src/Main.kt"))))
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.conflicted).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }

        runComposeUiTest {
            val unsupported = ApplyOutcome.Unsupported(UnsupportedReason.THREE_WAY_REQUIRED)
            val state = loadedState(FakePatchActions(dryRunOutcome = unsupported))
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.unsupportedThreeWay).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }

        runComposeUiTest {
            // copy·rename·mode 변경·binary 가 섞인 패치 — 3-way 와 다른 사유이므로 문구도 달라야 한다.
            val unsupported = ApplyOutcome.Unsupported(UnsupportedReason.REVERSE_NOT_PURE_HUNK)
            val state = loadedState(FakePatchActions(dryRunOutcome = unsupported))
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.unsupportedReverse).assertIsDisplayed()
            onAllNodesWithText(copy.unsupportedThreeWay).fetchSemanticsNodes().isEmpty() shouldBe true
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }

        runComposeUiTest {
            val violation = UndineException.StateViolation("패치가 저장소 밖 경로를 건드립니다")
            val state = loadedState(FakePatchActions(dryRunFailure = violation))
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.blockedStateViolation(violation.detail)).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }
    }

    test("빈 패치는 변경 없음으로 안내하고 적용을 막는다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions(dryRunOutcome = ApplyOutcome.NoChange), patch = ByteArray(0))
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.noChange).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }
    }

    test("커밋 모드는 메타데이터가 없으면 입력을 요구하며 미리 막고, 있으면 읽기 전용으로 보여 준다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions())
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            onNodeWithTag(PatchTags.mode("CREATE_COMMIT")).performClick()
            waitForIdle()

            onNodeWithTag(PatchTags.COMMIT_AUTHOR_NAME).assertIsDisplayed()
            onNodeWithTag(PatchTags.COMMIT_MESSAGE).assertIsDisplayed()
            onNodeWithText(copy.commitInputRequired).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsNotEnabled()
        }

        runComposeUiTest {
            val formatted = """
                From: A B <a@b.c>
                Subject: 패치 제목

                ---
                diff --git a/src/Main.kt b/src/Main.kt
                --- a/src/Main.kt
                +++ b/src/Main.kt
                @@ -1,1 +1,1 @@
                -old
                +new
            """.trimIndent().toByteArray()
            val state = loadedState(FakePatchActions(), patch = formatted)
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            onNodeWithTag(PatchTags.mode("CREATE_COMMIT")).performClick()
            waitForIdle()

            onNodeWithTag(PatchTags.COMMIT_METADATA).assertIsDisplayed()
            onNodeWithText(copy.fromPatchMetadata).assertIsDisplayed()
            onAllNodesWithTag(PatchTags.COMMIT_AUTHOR_NAME).fetchSemanticsNodes().isEmpty() shouldBe true
            onNodeWithTag(PatchTags.APPLY).assertIsEnabled()
        }
    }

    test("미리보기는 읽기 전용이다 — 같은 diff 형태를 그리되 스테이징 버튼이 없다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions())
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithTag(PatchTags.PREVIEW).assertIsDisplayed()
            onNodeWithText("old").assertIsDisplayed()
            onNodeWithText("new").assertIsDisplayed()
            onAllNodesWithTag("diff.hunk.0.stage").fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    test("분할 보기로 바꾸면 삭제·추가가 한 행에 짝지어 그려지고 스테이징 조작은 여전히 없다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions())
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            // 통합 보기에서는 삭제 줄과 추가 줄이 각각 제 행을 갖는다 (헤더 0 · 삭제 1 · 추가 2).
            onAllNodesWithTag(DiffTags.row(2)).fetchSemanticsNodes().size shouldBe 1

            onNodeWithTag(PatchTags.PREVIEW_SPLIT).performClick()
            waitForIdle()

            state.previewViewMode shouldBe DiffViewMode.SPLIT
            // 분할 보기에서는 둘이 한 행으로 합쳐져 세 번째 행이 사라진다.
            onAllNodesWithTag(DiffTags.row(2)).fetchSemanticsNodes().isEmpty() shouldBe true
            onNodeWithText("old").assertIsDisplayed()
            onNodeWithText("new").assertIsDisplayed()
            onAllNodesWithTag(DiffTags.stageHunk(0)).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    test("1 MiB 를 넘는 패치는 미리보기만 접고 적용은 막지 않는다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions(), patch = OVERSIZED_PATCH)
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.previewTooLarge).assertIsDisplayed()
            onNodeWithText(copy.previewStillApplicable).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsEnabled()
        }
    }

    test("binary 미리보기 안내는 적용을 막지 않는다") {
        runComposeUiTest {
            val state = loadedState(FakePatchActions(), patch = BINARY_PATCH)
            setContent { ScreenUnderTest(state) }
            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            waitForIdle()

            onNodeWithText(copy.previewBinary).assertIsDisplayed()
            onNodeWithText(copy.previewStillApplicable).assertIsDisplayed()
            onNodeWithTag(PatchTags.APPLY).assertIsEnabled()
        }
    }

    test("영어 로케일에서도 같은 화면이 그 로케일 문구로 그려진다") {
        runComposeUiTest {
            val state = state(FakePatchActions())
            setContent { ScreenUnderTest(state, Locale.ENGLISH) }

            onNodeWithText(englishCopy.title).assertIsDisplayed()
            // 선택 표식(●·○)이 앞에 붙으므로 부분 일치로 본다 — 검증 대상은 로케일 문구다.
            onNodeWithText(englishCopy.scopeAll, substring = true).assertIsDisplayed()
            onNodeWithText(englishCopy.modeWorkingTree, substring = true).assertIsDisplayed()
        }
    }
})

@Composable
private fun ScreenUnderTest(state: PatchState, locale: Locale = DEFAULT_LOCALE) {
    val strings = catalog.stringsFor(locale, devBuild = false)
    CompositionLocalProvider(LocalStrings provides strings) {
        PatchScreen(state = state, modifier = Modifier, strings = strings)
    }
}

private fun state(
    actions: FakePatchActions,
    files: FakePatchFiles = FakePatchFiles(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): PatchState = PatchState(actions, files, CoroutineScope(dispatcher), dispatcher).also { it.load() }

private fun loadedState(actions: FakePatchActions, patch: ByteArray = COMBINED_PATCH): PatchState {
    val target = path("/tmp/fix.patch")
    return state(actions, FakePatchFiles(openChoice = target, contents = mapOf(target to patch)))
}
