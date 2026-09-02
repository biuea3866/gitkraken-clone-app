package dev.undine.presentation.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.undine.infrastructure.git.submodule.seedRepository
import dev.undine.presentation.AppDestination
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/** 결정 G45: WCAG 1.4.4 가 요구하는 상한. */
private const val LARGE_FONT_SCALE = 2.0f

/**
 * 조작 가능한 노드(클릭 동작을 가진 노드)를 **형태를 묻지 않고** 훑는 감사.
 *
 * 결정 G44-3: 아이콘 전용 버튼을 `Icon`·`Image`·커스텀으로 열거해 검사하면 빠뜨린 형태가 조용히
 * 통과한다. 시맨틱스 트리에서 클릭 가능한 노드 **전부**를 훑으면 구현 형태를 몰라도 성립하고,
 * 새 형태가 들어와도 자동으로 걸린다.
 *
 * 감사는 **조립된 앱**을 대상으로 한다 — 배선된 일곱 화면을 차례로 열어 그 화면이 실제로 내보내는
 * 시맨틱스를 본다.
 */
@OptIn(ExperimentalTestApi::class)
class OperableNodeAuditSpec : FunSpec({

    test("배선된 모든 화면에서 클릭 가능한 노드는 비어 있지 않은 의미 레이블을 가진다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("레이블.txt")
        rememberAsRecent(settingsFile, work)

        runComposeUiTest {
            val wiring = startApp(settingsFile)
            openRecent(wiring, work)

            val unlabeled = mutableListOf<String>()
            var audited = 0
            AppDestination.entries.forEach { destination ->
                wiring.navigation.go(destination)
                waitForIdle()
                operableNodes().forEach { node ->
                    audited += 1
                    if (node.accessibleLabel().isBlank()) {
                        unlabeled += "${destination.name}: ${node.describe()}"
                    }
                }
            }

            // 훑은 노드가 0 이면 목록이 비어 통과한 것이라 감사가 아니다.
            audited shouldBeGreaterThan 0
            unlabeled.shouldBeEmpty()
        }
    }

    // 결정 G45-2: 판정 기준은 "레이아웃이 예쁘다" 가 아니라 "사라지지 않는다" 다.
    test("fontScale 1.0 에서 조작할 수 있던 노드는 fontScale 2.0 에서도 표시된다") {
        val settingsFile = File(tempdir(), "settings.json").toPath()
        val work = seedRepository("확대.txt")
        rememberAsRecent(settingsFile, work)

        runComposeUiTest {
            // 같은 앱을 그대로 두고 밀도만 바꾼다 — 앱을 다시 띄우면 확대와 무관한 차이(조회 진행
            // 상태 등)가 섞여 무엇이 사라진 것인지 말할 수 없다.
            val fontScale = mutableStateOf(1.0f)
            val wiring = startApp(settingsFile) { content ->
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale.value),
                    content = content,
                )
            }
            openRecent(wiring, work)

            val atDefault = operableNodeCensusByDestination(wiring)
            fontScale.value = LARGE_FONT_SCALE
            waitForIdle()
            val atLarge = operableNodeCensusByDestination(wiring)

            atDefault.values.sumOf { census -> census.values.sum() } shouldBeGreaterThan 0
            // 같은 서명을 가진 노드가 여럿일 수 있으므로 **개수까지** 견준다 — 집합으로 비교하면
            // 열 줄짜리 목록에서 아홉 줄이 잘려 나가도 서명 하나가 남아 통과한다.
            val missing = atDefault.flatMap { (destination, census) ->
                val large = atLarge.getValue(destination)
                census.mapNotNull { (signature, count) ->
                    val survived = large[signature] ?: 0
                    if (survived >= count) null else "$destination: $signature ($count → $survived)"
                }
            }
            missing.shouldBeEmpty()
        }
    }
})

/** 지금 그려진 화면에서 클릭 동작을 가진 노드 전부. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.operableNodes(): List<SemanticsNode> =
    onAllNodes(hasClickAction()).fetchSemanticsNodes()

/**
 * 화면별 조작 가능 노드의 **서명별 개수**. 확대 전후를 이 값으로 대조한다.
 *
 * 서명은 배율이 바뀌어도 그대로인 것들로만 만든다 — 테스트 태그·역할·레이블이다. 좌표·크기는
 * 확대하면 당연히 달라지므로 동일성 기준이 될 수 없다. 개수를 함께 세는 이유는 같은 서명의 노드가
 * 여럿(목록의 행들)일 때 그중 일부가 사라지는 것을 잡기 위해서다.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.operableNodeCensusByDestination(
    wiring: dev.undine.presentation.AppWiring,
): Map<String, Map<String, Int>> = AppDestination.entries.associate { destination ->
    wiring.navigation.go(destination)
    waitForIdle()
    destination.name to operableNodes()
        .filter { it.isDisplayed() }
        .map { it.stableSignature() }
        .groupingBy { signature -> signature }
        .eachCount()
}

/** 배율이 바뀌어도 유지되는 노드 식별자 — 태그가 있으면 태그가, 없으면 역할과 레이블이 대신한다. */
private fun SemanticsNode.stableSignature(): String {
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    val role = config.getOrNull(SemanticsProperties.Role)
    return "tag=$tag role=$role label=${accessibleLabel()}"
}

/**
 * 스크린리더가 이 노드에서 읽어 낼 문자열.
 *
 * 병합 노드는 자기 설정에 자손의 텍스트가 이미 합쳐져 있으나, 병합하지 않는 노드는 그렇지 않다 —
 * 두 경우를 같게 다루려고 자손까지 내려가 모은다.
 */
private fun SemanticsNode.accessibleLabel(): String =
    (ownLabel() + children.map { it.accessibleLabel() }).joinToString(" ").trim()

private fun SemanticsNode.ownLabel(): List<String> = listOfNotNull(
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
    config.getOrNull(SemanticsProperties.EditableText)?.text,
)

/** 화면에 실제로 자리를 차지하고 있는가. 잘려 사라진 노드는 크기가 0 이다. */
private fun SemanticsNode.isDisplayed(): Boolean = size.width > 0 && size.height > 0

/** 레이블이 없는 노드를 사람이 찾아갈 수 있게 남기는 표식. */
private fun SemanticsNode.describe(): String {
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    val role = config.getOrNull(SemanticsProperties.Role)
    val label = config.getOrNull(SemanticsActions.OnClick)?.label
    return "tag=$tag role=$role action=$label bounds=$boundsInRoot"
}
