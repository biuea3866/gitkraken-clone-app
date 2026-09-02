package dev.undine.presentation.sidebar

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.undine.application.sidebar.SidebarRefs
import dev.undine.domain.Branch
import dev.undine.domain.DeleteBranchResult
import dev.undine.domain.OpenedRepository
import dev.undine.domain.RefName
import dev.undine.domain.RepositoryState
import dev.undine.domain.ThemeMode
import dev.undine.domain.UndineException
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.StringCatalog
import dev.undine.presentation.i18n.sidebar
import dev.undine.presentation.i18n.sidebarTranslations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify

private val SIDEBAR_WIDTH = 320.dp
private val SIDEBAR_HEIGHT = 700.dp

/** shell 과 마찬가지로 sidebar 네임스페이스는 아직 내장 목록에 없어 자기 맵으로 만든다 (결정 A3). */
private val CATALOG = StringCatalog(translations = sidebarTranslations, defaultLocale = DEFAULT_LOCALE)
private val SIDEBAR_STRINGS = CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false).sidebar

@Composable
private fun SidebarHost(
    state: SidebarState,
    opened: OpenedRepository? = OpenedRepository(RepositoryState.NORMAL, RefName("main")),
    onMergeSourceSelected: (Branch) -> Unit = {},
) {
    UndineTheme(themeMode = ThemeMode.LIGHT) {
        CompositionLocalProvider(
            LocalStrings provides CATALOG.stringsFor(DEFAULT_LOCALE, devBuild = false),
        ) {
            SidebarTree(
                state = state,
                modifier = Modifier.size(SIDEBAR_WIDTH, SIDEBAR_HEIGHT),
                opened = opened,
                onMergeSourceSelected = onMergeSourceSelected,
            )
        }
    }
}

/** 사이드바 화면 — 그룹 표시·접힘·배지·필터·빈 상태·detached 안내·삭제 확인·키보드 경로. */
@OptIn(ExperimentalTestApi::class)
class SidebarTreeSpec : FunSpec({

    test("로컬·원격 브랜치, 태그, 스태시가 각 그룹으로 묶여 표시된다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            SidebarGroup.entries.forEach {
                onNodeWithTag(SidebarTags.group(it)).assertIsDisplayed()
            }
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_MAIN)).assertIsDisplayed()
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_REMOTE_MAIN)).assertIsDisplayed()
            onNodeWithText("v1.0.0").assertIsDisplayed()
            onNodeWithText("작업 중", substring = true).assertIsDisplayed()
        }
    }

    test("그룹 헤더를 누르면 그 그룹 항목이 접히고 다시 누르면 펼쳐진다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.group(SidebarGroup.LOCAL_BRANCHES)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_MAIN)).assertDoesNotExist()
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_REMOTE_MAIN)).assertIsDisplayed()

            onNodeWithTag(SidebarTags.group(SidebarGroup.LOCAL_BRANCHES)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_MAIN)).assertIsDisplayed()
        }
    }

    test("현재 체크아웃된 브랜치만 현재 표시로 구분된다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.currentMarker(RefName("main"))).assertIsDisplayed()
            onNodeWithTag(SidebarTags.currentMarker(RefName("feature/login"))).assertDoesNotExist()
        }
    }

    test("ahead·behind 가 있으면 배지로 표시하고 둘 다 0 이면 감춘다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.badge(RefName("main"))).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.ahead(2), substring = true).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.behind(1), substring = true).assertIsDisplayed()
            onNodeWithTag(SidebarTags.badge(RefName("feature/login"))).assertDoesNotExist()
        }
    }

    test("한쪽만 0 이면 0 인 쪽 배지 문구가 나오지 않는다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded(
                SidebarRefs(
                    branches = listOf(branchOf("main", isCurrent = true, ahead = 3, behind = 0)),
                    tags = emptyList(),
                    stashes = emptyList(),
                ),
            )
            setContent { SidebarHost(state) }

            onNodeWithText(SIDEBAR_STRINGS.ahead(3), substring = true).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.behind(0), substring = true).assertDoesNotExist()
        }
    }

    test("필터를 입력하면 브랜치 목록이 좁혀진다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.FILTER).performTextInput("login")
            waitForIdle()

            onNodeWithTag(SidebarTags.branchRow(SAMPLE_FEATURE)).assertIsDisplayed()
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_MAIN)).assertDoesNotExist()
        }
    }

    test("표시할 브랜치가 없으면 빈 상태 안내가 표시된다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded(
                SidebarRefs(branches = emptyList(), tags = emptyList(), stashes = emptyList()),
            )
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.EMPTY).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.emptyBranches).assertIsDisplayed()
        }
    }

    test("필터에 걸리는 브랜치가 없으면 필터 문자열을 담은 빈 상태 안내가 표시된다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.FILTER).performTextInput("zzz")
            waitForIdle()

            onNodeWithTag(SidebarTags.EMPTY).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.emptyFiltered("zzz")).assertIsDisplayed()
        }
    }

    test("detached HEAD 상태가 트리 상단에 명시된다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent {
                SidebarHost(state, opened = OpenedRepository(RepositoryState.DETACHED, currentBranch = null))
            }

            onNodeWithTag(SidebarTags.DETACHED).assertIsDisplayed()
            onNodeWithText(SIDEBAR_STRINGS.detachedHead).assertIsDisplayed()
        }
    }

    test("브랜치가 있는 정상 상태에서는 detached 안내가 나오지 않는다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.DETACHED).assertDoesNotExist()
        }
    }

    test("목록 조회가 실패하면 빈 상태가 아니라 실패 안내가 표시된다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            coEvery { harness.refGateway.listBranches() } throws
                UndineException.GitOperationFailed("ref.listBranches")
            harness.state.refresh()
            setContent { SidebarHost(harness.state) }

            onNodeWithTag(SidebarTags.ERROR).assertIsDisplayed()
            onNodeWithTag(SidebarTags.EMPTY).assertDoesNotExist()
        }
    }

    test("컨텍스트 메뉴는 체크아웃·이름 변경·삭제·병합 대상 선택을 제공한다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()

            onNodeWithTag(SidebarTags.MENU_CHECKOUT).assertIsDisplayed()
            onNodeWithTag(SidebarTags.MENU_RENAME).assertIsDisplayed()
            onNodeWithTag(SidebarTags.MENU_DELETE).assertIsDisplayed()
            onNodeWithTag(SidebarTags.MENU_MERGE).assertIsDisplayed()
        }
    }

    test("원격 추적 브랜치 메뉴에는 로컬 전용 이름 변경·삭제가 없다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_REMOTE_MAIN)).performClick()
            waitForIdle()

            // 이름 변경·삭제는 refs/heads/ 를 대상으로 한다 — 원격 행에 노출되면 동명 로컬 브랜치가 지워진다.
            onNodeWithTag(SidebarTags.MENU_RENAME).assertDoesNotExist()
            onNodeWithTag(SidebarTags.MENU_DELETE).assertDoesNotExist()
            // 원격 행에서도 할 수 있는 것은 그대로 있어야 한다.
            onNodeWithTag(SidebarTags.MENU_CHECKOUT).assertIsDisplayed()
            onNodeWithTag(SidebarTags.MENU_MERGE).assertIsDisplayed()
        }
    }

    test("이름이 겹치는 로컬 행에는 이름 변경·삭제가 그대로 있다") {
        runComposeUiTest {
            // 원격과 같은 짧은 이름을 가진 로컬 브랜치. 숨김 조건이 이름이 아니라 ref 종류임을 못박는다.
            val localSameName = branchOf("origin/main")
            val state = SidebarStateHarness().loaded(
                sampleRefs().copy(branches = listOf(localSameName, SAMPLE_REMOTE_MAIN)),
            )
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(localSameName)).performClick()
            waitForIdle()

            onNodeWithTag(SidebarTags.MENU_RENAME).assertIsDisplayed()
            onNodeWithTag(SidebarTags.MENU_DELETE).assertIsDisplayed()
        }
    }

    test("병합 대상 선택은 고른 브랜치를 콜백으로 넘긴다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            var selected: Branch? = null
            setContent { SidebarHost(state, onMergeSourceSelected = { selected = it }) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_MERGE).performClick()
            waitForIdle()

            selected?.name shouldBe RefName("feature/login")
        }
    }

    test("미병합 브랜치 삭제는 도달 불가 경고를 확인해야 강제 삭제된다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            coEvery { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) } returns
                DeleteBranchResult.REFUSED_UNMERGED
            coEvery { harness.refGateway.deleteBranch(RefName("feature/login"), force = true) } returns
                DeleteBranchResult.DELETED
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_DELETE).performClick()
            waitForIdle()

            // 1단계 — 삭제 확인. 아직 어떤 삭제도 실행되지 않았다.
            onNodeWithTag(SidebarTags.CONFIRM_DIALOG).assertIsDisplayed()
            coVerify(exactly = 0) { harness.refGateway.deleteBranch(any(), any()) }

            onNodeWithTag(SidebarTags.CONFIRM_ACCEPT).performClick()
            waitForIdle()

            // 2단계 — 비강제 삭제가 거부돼 도달 불가 경고가 뜬다.
            onNodeWithText(SIDEBAR_STRINGS.unmergedMessage("feature/login")).assertIsDisplayed()
            coVerify(exactly = 0) { harness.refGateway.deleteBranch(RefName("feature/login"), force = true) }

            onNodeWithTag(SidebarTags.CONFIRM_ACCEPT).performClick()
            waitForIdle()

            coVerify(exactly = 1) { harness.refGateway.deleteBranch(RefName("feature/login"), force = true) }
        }
    }

    test("도달 불가 경고에서 취소하면 강제 삭제가 실행되지 않는다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            coEvery { harness.refGateway.deleteBranch(RefName("feature/login"), force = false) } returns
                DeleteBranchResult.REFUSED_UNMERGED
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_DELETE).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.CONFIRM_ACCEPT).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.CONFIRM_CANCEL).performClick()
            waitForIdle()

            onNodeWithTag(SidebarTags.CONFIRM_DIALOG).assertDoesNotExist()
            coVerify(exactly = 0) { harness.refGateway.deleteBranch(RefName("feature/login"), force = true) }
        }
    }

    // 대화상자를 마우스로만 닫을 수 있으면 키보드만 쓰는 사용자는 갇힌다 (UND-50 접근성 감사).
    test("삭제 확인은 ESC 로 닫히고 어떤 삭제도 실행하지 않는다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_DELETE).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.CONFIRM_DIALOG).assertIsDisplayed()

            onNodeWithTag(SidebarTags.CONFIRM_CANCEL).requestFocus()
            onNodeWithTag(SidebarTags.CONFIRM_CANCEL).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(SidebarTags.CONFIRM_DIALOG).assertDoesNotExist()
            coVerify(exactly = 0) { harness.refGateway.deleteBranch(any(), any()) }
        }
    }

    test("삭제 확인이 열려 있는 동안 포커스는 대화상자 밖으로 나가지 않는다") {
        runComposeUiTest {
            val state = SidebarStateHarness().loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_DELETE).performClick()
            waitForIdle()

            onNodeWithTag(SidebarTags.CONFIRM_ACCEPT).requestFocus()
            onNodeWithTag(SidebarTags.CONFIRM_ACCEPT).performKeyInput { pressKey(Key.Tab) }
            waitForIdle()

            // 뒤에 가린 브랜치 목록으로 Tab 이 새면 확인하지 않은 채 다른 브랜치를 만진다.
            onNodeWithTag(SidebarTags.branchRow(SAMPLE_MAIN)).assertIsNotFocused()
        }
    }

    test("이름 변경 대화상자도 ESC 로 닫힌다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_RENAME).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.RENAME_DIALOG).assertIsDisplayed()

            onNodeWithTag(SidebarTags.RENAME_CANCEL).requestFocus()
            onNodeWithTag(SidebarTags.RENAME_CANCEL).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(SidebarTags.RENAME_DIALOG).assertDoesNotExist()
            coVerify(exactly = 0) { harness.refGateway.renameBranch(any(), any()) }
        }
    }

    test("이름 변경은 대화상자에 입력한 새 이름으로 요청된다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_RENAME).performClick()
            waitForIdle()
            onNodeWithTag(SidebarTags.RENAME_FIELD).performTextInput("-v2")
            onNodeWithTag(SidebarTags.RENAME_ACCEPT).performClick()
            waitForIdle()

            coVerify(exactly = 1) {
                harness.refGateway.renameBranch(RefName("feature/login"), RefName("feature/login-v2"))
            }
        }
    }

    test("키보드만으로 메뉴를 열고 체크아웃을 실행할 수 있다") {
        runComposeUiTest {
            val harness = SidebarStateHarness()
            val state = harness.loaded()
            setContent { SidebarHost(state) }

            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE)).requestFocus()
            onNodeWithTag(SidebarTags.menuButton(SAMPLE_FEATURE))
                .performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            onNodeWithTag(SidebarTags.MENU_CHECKOUT).requestFocus()
            onNodeWithTag(SidebarTags.MENU_CHECKOUT).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            coVerify(exactly = 1) { harness.refGateway.checkout(RefName("feature/login"), force = false) }
        }
    }

    test("브랜치가 수백 개여도 안정 키로 목록을 그리고 마지막 항목까지 스크롤된다") {
        runComposeUiTest {
            val many = (0 until 300).map { branchOf("feature/branch-$it") }
            val state = SidebarStateHarness().loaded(
                SidebarRefs(branches = many, tags = emptyList(), stashes = emptyList()),
            )
            setContent { SidebarHost(state) }

            val lastKey = SidebarNode.BranchRow(many.last()).key
            onNodeWithTag(SidebarTags.LIST).performScrollToKey(lastKey)
            waitForIdle()

            onNodeWithTag(SidebarTags.branchRow(many.last())).assertIsDisplayed()
        }
    }
})
