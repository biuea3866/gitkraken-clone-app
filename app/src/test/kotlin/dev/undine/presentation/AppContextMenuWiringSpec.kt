package dev.undine.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.domain.Branch
import dev.undine.domain.BranchTarget
import dev.undine.domain.CommitId
import dev.undine.domain.RefName
import dev.undine.domain.graphops.GraphOperation
import dev.undine.infrastructure.git.submodule.seedRepository
import dev.undine.presentation.a11y.WAIT_MILLIS
import dev.undine.presentation.a11y.openRecent
import dev.undine.presentation.a11y.rememberAsRecent
import dev.undine.presentation.a11y.startApp
import dev.undine.presentation.contextmenu.ContextMenuTags
import dev.undine.presentation.contextmenu.GraphOperationKind
import dev.undine.presentation.graph.GraphTags
import dev.undine.presentation.sidebar.SidebarTags
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import org.eclipse.jgit.api.Git
import java.io.File

private val FEATURE = RefName("feature")

/** 사이드바 행 태그는 ref 이름과 종류만 보므로 나머지 필드는 표시에 쓰이지 않는다. */
private fun featureRow(): Branch = Branch(
    name = FEATURE,
    target = CommitId.of("0".repeat(40)),
    isCurrent = false,
    isRemote = false,
    upstream = null,
    ahead = 0,
    behind = 0,
)

/**
 * **조립된 앱**에서 우클릭 진입점이 실제 실행 경로에 닿는지.
 *
 * 화면 하나를 손으로 띄우면 그 화면이 실제 배선에서 콜백을 받는지는 말하지 않는다 — 이 티켓이
 * 고친 두 결함(사이드바 병합 콜백 누락·컨텍스트 메뉴 부재)이 정확히 그 틈에 있었다 (결정 D9).
 */
@OptIn(ExperimentalTestApi::class)
class AppContextMenuWiringSpec : FunSpec({

    test("사이드바에서 고른 병합 대상이 실제 병합 실행 경로에 도달한다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = repositoryWithFeatureBranch()
        rememberAsRecent(settingsFile, work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithTag(SidebarTags.branchRow(featureRow())).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag(SidebarTags.branchRow(featureRow())).performMouseInput { rightClick() }
            waitForIdle()
            onNodeWithTag(SidebarTags.MENU_MERGE).performClick()
            waitForIdle()

            // 콜백을 넘기지 않아 기본값 `{}` 가 쓰이던 동안 이 확인창은 열리지 않았다 (결정 D9-1).
            wiring.dragDrop.confirmation.shouldNotBeNull().choices shouldContainExactly listOf(
                GraphOperation.Merge(FEATURE, BranchTarget.Current),
            )
        }
    }

    test("그래프 커밋 행 우클릭이 조립된 앱에서 메뉴를 열고 기존 실행 경로로 보낸다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = repositoryWithFeatureBranch()
        rememberAsRecent(settingsFile, work)
        val head = headCommitOf(work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)
            waitUntil(timeoutMillis = WAIT_MILLIS) {
                onAllNodesWithTag(GraphTags.row(head)).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag(GraphTags.row(head)).performMouseInput { rightClick() }
            waitForIdle()

            // 배선이 없으면 메뉴 자체가 그려지지 않는다 — 행에 트리거가 붙지 않기 때문이다.
            onNodeWithTag(ContextMenuTags.MENU).assertIsDisplayed()
            onNodeWithTag(ContextMenuTags.item(GraphOperationKind.CHERRY_PICK)).performClick()
            waitForIdle()

            wiring.dragDrop.confirmation.shouldNotBeNull().choices shouldContainExactly listOf(
                GraphOperation.CherryPick(head, BranchTarget.Current),
            )
        }
    }
})

/** 병합 대상이 될 브랜치를 하나 더 둔 저장소. 현재 브랜치 자신은 병합 대상이 될 수 없다. */
private fun TestConfiguration.repositoryWithFeatureBranch(): File {
    val directory = seedRepository("병합.txt")
    Git.open(directory).use { opened -> opened.branchCreate().setName(FEATURE.value).call() }
    return directory
}

private fun headCommitOf(repository: File): CommitId =
    Git.open(repository).use { opened -> CommitId.of(requireNotNull(opened.repository.resolve("HEAD")).name) }
