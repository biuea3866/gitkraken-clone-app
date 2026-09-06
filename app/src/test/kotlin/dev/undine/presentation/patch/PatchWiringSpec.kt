package dev.undine.presentation.patch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import dev.undine.domain.RepositoryPath
import dev.undine.domain.RepositorySessionKey
import dev.undine.domain.patch.PatchExport
import dev.undine.presentation.AppDestination
import dev.undine.presentation.AppDestinationTags
import dev.undine.presentation.AppNavigationState
import dev.undine.presentation.PatchArea
import dev.undine.presentation.SecondaryScreen
import dev.undine.presentation.availabilityOf
import dev.undine.presentation.i18n.DEFAULT_LOCALE
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.patch
import dev.undine.presentation.palette.CommandAvailability
import dev.undine.presentation.shell.ActiveRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred

private val strings = builtInStringCatalog().stringsFor(DEFAULT_LOCALE, devBuild = false)
private val copy = strings.patch

/** 같은 카탈로그의 다른 로케일. 대화상자 제목을 포함한 화면 문구 전부가 바뀐다. */
private val englishStrings: Strings = builtInStringCatalog().stringsFor(Locale.ENGLISH, devBuild = false)
private val englishCopy = englishStrings.patch

/** 대화상자·게이트웨이만 대역이고 나머지는 실제 배선이다 — 저장소 자체는 여기서 필요 없다. */
private val OPERABLE = ActiveRepository.Operable(RepositoryPath("/tmp/undine"))

/** 저장소 정체성. 홀더가 정규화해 돌려준 값을 흉내 낸다 — 경로 문자열이 아니라 이 값이 키다. */
private val SESSION_A = RepositorySessionKey("/tmp/undine-a")
private val SESSION_B = RepositorySessionKey("/tmp/undine-b")

private val PATCH_BYTES = """
    diff --git a/src/Main.kt b/src/Main.kt
    --- a/src/Main.kt
    +++ b/src/Main.kt
    @@ -1,1 +1,1 @@
    -old
    +new
""".trimIndent().toByteArray()

/** 화면이 그릴 것이 있어야 생성 결과가 보인다 — 경로 하나면 충분하다. */
private val EXPORT = PatchExport(perCommit = emptyList(), combined = PATCH_BYTES)

/** 화면 안쪽이 아니라 **배선**을 본다 — 배선이 얼마나 오래 걸릴지는 대역이 잡고 있는 시간이 정한다. */
/**
 * 렌더가 끝나기를 기다리는 **행 방지 장치**다. 검증 대상이 아니므로 넉넉해야 한다.
 *
 * CI 는 `xvfb-run` 가상 디스플레이에서 `--no-daemon` 으로 돌고, `composeTest` 는 `forkEvery = 1`
 * 이라 스펙마다 콜드 JVM 이다 — 소프트웨어 렌더링 첫 프레임이 로컬(GPU)보다 훨씬 느리다.
 * 10초로 두었더니 **렌더된 노드를 기다리는 대기만** 골라 시간 초과했고(렌더가 필요 없는
 * `commitLoads` 대기는 통과했다), 같은 성격인 [dev.undine.presentation.AppAssemblySpec] 이
 * 이미 30초를 쓴다. 그 값에 맞춘다 — 상태는 게이트가 붙잡고 있으므로 늘려도 검증이 무뎌지지 않는다.
 */
private const val WAIT_MILLIS = 30_000L

/**
 * PATCH 목적지의 실제 Compose 배선.
 *
 * `PatchStateSpec` 은 차단 판정을 **테스트가 직접 등록해** 확인한다 — 그러면 배선에서
 * `DisposableEffect` 등록·해제나 `SecondaryScreen` 의 표시가 통째로 사라져도 초록불이다. 여기서는
 * 배선이 만든 홀더와 실제 껍데기 위에서, 적용이 도는 동안 나가는 길이 잠기고 끝나면 풀리는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
class PatchWiringSpec : FunSpec({

    test("배선된 화면에서 적용이 도는 동안 뒤로 가기와 팔레트 이동이 같은 사유로 막히고, 끝나면 풀린다") {
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val navigation = AppNavigationState(AppDestination.PATCH)
            val target = path("/tmp/fix.patch")
            val files = FakePatchFiles(openChoice = target, contents = mapOf(target to PATCH_BYTES))
            setContent { WiringUnderTest(navigation, FakePatchActions(applyGate = gate), files) }

            onNodeWithTag(PatchTags.CHOOSE_FILE).performClick()
            awaitOrDump("changedFiles") {
                onAllNodesWithText(copy.changedFiles).fetchSemanticsNodes().isNotEmpty()
            }
            // 적용 전에는 나가는 길이 열려 있다 — 읽기·검사는 저장소를 바꾸지 않는다.
            onNodeWithText(backLabel).assertIsEnabled()

            onNodeWithTag(PatchTags.APPLY).performClick()
            waitUntil(timeoutMillis = WAIT_MILLIS) { exitBlockedShown() }

            // 사유를 그 자리에 말한다 — 눌리지 않는 버튼만 두면 사용자는 앱이 멈춘 줄로 안다.
            onNodeWithText(copy.exitBlocked).assertIsDisplayed()
            onNodeWithText(backLabel).assertIsNotEnabled()
            navigation.go(AppDestination.REPOSITORY)
            navigation.destination shouldBe AppDestination.PATCH

            // 팔레트도 같은 판정을 본다 — 한쪽만 막으면 다른 쪽으로 빠져나가 적용이 끊긴다.
            availabilityOf(
                AppDestination.REPOSITORY,
                OPERABLE,
                strings,
                navigation.exitBlockedReason(AppDestination.REPOSITORY),
            ).shouldBeInstanceOf<CommandAvailability.Blocked>().reason shouldBe copy.exitBlocked

            // 저장소 열기·닫기도 **같은 판정 하나**를 본다 (결정 C6) — 이동만 막고 세션 전환을 열어 두면
            // 검사한 저장소와 다른 저장소가 활성인 채로 적용이 끝난다.
            navigation.activeJobBlockedReason() shouldBe copy.exitBlocked

            gate.complete(Unit)
            waitUntil(timeoutMillis = WAIT_MILLIS) { !exitBlockedShown() }

            onNodeWithText(backLabel).assertIsEnabled()
            navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
            navigation.activeJobBlockedReason().shouldBeNull()
        }
    }

    test("화면을 떠나면 등록이 거둬져 다음 이동이 막히지 않는다") {
        runComposeUiTest {
            val navigation = AppNavigationState(AppDestination.PATCH)
            val actions = FakePatchActions()
            val files = FakePatchFiles()
            setContent { WiringUnderTest(navigation, actions, files) }

            onNodeWithTag(PatchTags.ROOT).assertExists()
            navigation.go(AppDestination.REPOSITORY)
            waitForIdle()

            onNodeWithTag(AppDestinationTags.of(AppDestination.REPOSITORY)).assertExists()
            navigation.exitBlockedReason(AppDestination.REPOSITORY).shouldBeNull()
            navigation.go(AppDestination.PREFERENCES)
            navigation.destination shouldBe AppDestination.PREFERENCES
        }
    }

    // 저장소 A 에서 통과한 검사를 들고 B 로 넘어가면, UND-60 이 지킨 원자성이 **엉뚱한 저장소 위에서**
    // 성립한다 (결정 C6). 배선이 홀더를 세션 정체성에 걸어야만 이 테스트가 초록불이 된다.
    test("활성 저장소가 바뀌면 배선이 화면 상태를 버리고 그 저장소의 목록을 다시 읽는다") {
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val navigation = AppNavigationState(AppDestination.PATCH)
            val actions = FakePatchActions(exportResult = EXPORT, exportGate = gate)
            var sessionKey by mutableStateOf(SESSION_A)
            // 대역은 컴포지션 **밖에서** 한 번 만든다 — 리컴포지션마다 새로 만들면 홀더가 그 때문에
            // 갈리고, 세션 키를 무시하는 배선에서도 이 테스트가 초록불이 된다.
            val files = FakePatchFiles()
            setContent { WiringUnderTest(navigation, actions, files, sessionKey) }

            waitUntil(timeoutMillis = WAIT_MILLIS) { actions.commitLoads == 1 }
            onNodeWithTag(PatchTags.PREPARE).performClick()
            awaitOrDump("preparing") { textShown(copy.preparing) }

            sessionKey = SESSION_B
            waitForIdle()

            // A 에서 시작한 생성의 진행·결과가 B 화면에 남지 않는다.
            textShown(copy.preparing) shouldBe false
            waitUntil(timeoutMillis = WAIT_MILLIS) { actions.commitLoads == 2 }

            // 늦게 끝난 A 의 생성이 B 화면에 결과로 앉지도 않는다.
            gate.complete(Unit)
            waitForIdle()
            textShown(copy.includedFiles) shouldBe false
        }
    }

    // 배선의 **기본값**(`rememberAwtPatchFiles`)까지 포함해서 본다. 대화상자 대역을 주입하면 제목이
    // 바뀔 때 실제 구현이 새로 만들어지는지 알 수 없고, 그 재생성이 홀더까지 갈아 치우는 것이 이
    // 수리의 실패 경로다 — 진행 중이던 작업의 결과가 갈 곳을 잃는다.
    //
    // 여기서 대화상자를 여는 경로(`chooseOpenFile`·`chooseSaveFile`)는 건드리지 않는다. 실제 AWT 창이
    // 뜨면 테스트가 사람 조작을 기다리며 멈추기 때문이다 — 그래서 이 경로로는 적용·저장을 시작할 수
    // 없고, 진행 중 이탈 차단은 대역을 쓰는 위 첫 테스트가 덮는다. 여기서는 **같은 홀더 위에서** 도는
    // 생성 작업으로 "진행 중 상태와 결과가 살아남는가" 를 본다.
    test("대화상자 제목 제공값이 바뀌어도 기본 배선의 홀더가 유지돼 진행 중 작업과 그 결과가 살아남는다") {
        runComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val navigation = AppNavigationState(AppDestination.PATCH)
            val actions = FakePatchActions(exportResult = EXPORT, exportGate = gate)
            var catalog by mutableStateOf(strings)
            setContent {
                val provided = catalog
                CompositionLocalProvider(LocalStrings provides provided) {
                    Column {
                        // 배선이 대화상자에 넘길 제목 — 제공값이 실제로 갈렸는지 여기서 확인한다.
                        BasicText(provided.patch.openDialogTitle)
                        SecondaryScreen(navigation) {
                            PatchArea(patch = actions, navigation = navigation, sessionKey = SESSION_A)
                        }
                    }
                }
            }

            waitUntil(timeoutMillis = WAIT_MILLIS) { actions.commitLoads == 1 }
            onNodeWithTag(PatchTags.PREPARE).performClick()
            awaitOrDump("preparing") { textShown(copy.preparing) }
            textShown(copy.openDialogTitle) shouldBe true

            catalog = englishStrings
            waitUntil(timeoutMillis = WAIT_MILLIS) { textShown(englishCopy.openDialogTitle) }

            // 진행 중이던 작업이 그대로다. 홀더가 갈렸다면 유휴 상태로 돌아가 진행 표시가 사라지고,
            // 배선이 그 저장소의 목록을 처음부터 다시 읽는다.
            //
            // 문구는 **영어로** 본다 — 화면이 제공자를 따르므로 카탈로그를 바꾸면 같은 진행 상태가
            // 영어로 그려진다. 여기서 보는 것은 언어가 아니라 **그 상태가 살아남았는가**다.
            textShown(englishCopy.preparing) shouldBe true
            actions.commitLoads shouldBe 1

            // 결과도 갈 곳을 잃지 않는다 — 시작한 작업이 끝까지 이 화면에 도착한다.
            gate.complete(Unit)
            waitUntil(timeoutMillis = WAIT_MILLIS) { textShown(englishCopy.includedFiles) }
            actions.exportedScopes.size shouldBe 1
        }
    }
})

/** 차단 사유가 지금 화면에 있는가. 폴링 조건이라 노드 존재 여부로만 본다. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.exitBlockedShown(): Boolean =
    onAllNodesWithTag(AppDestinationTags.EXIT_BLOCKED).fetchSemanticsNodes().isNotEmpty()

/**
 * 진단용 — 대기가 시간 초과하면 **그 순간 화면에 실제로 무엇이 있는지** 찍고 다시 던진다.
 * CI 에서만 깨지는 대기의 원인을 추측으로 좁히지 않기 위해서다.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitOrDump(label: String, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMillis = WAIT_MILLIS, condition = condition)
    } catch (timeout: ComposeTimeoutException) {
        println("=== awaitOrDump 실패: $label ===")
        println(runCatching { onRoot().printToString(maxDepth = 100) }.getOrElse { "화면 덤프 실패: $it" })
        throw timeout
    }
}

/** 그 문구가 지금 화면에 있는가. 폴링 조건으로도, 단언으로도 같은 판정을 쓴다. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.textShown(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

/** 나가는 길의 문구. 껍데기가 붙이는 그대로를 집는다. */
private val backLabel: String
    get() = "← ${AppDestination.REPOSITORY.label}"

/**
 * `DestinationArea` 의 PATCH 분기와 **같은 조합**을 그린다 — 2차 화면 껍데기 안의 배선 컴포저블.
 * 대화상자와 게이트웨이만 대역이고, 홀더 생성·이탈 차단 등록은 배선이 하는 그대로다.
 */
@Composable
private fun WiringUnderTest(
    navigation: AppNavigationState,
    actions: FakePatchActions,
    files: FakePatchFiles,
    sessionKey: RepositorySessionKey = SESSION_A,
) {
    CompositionLocalProvider(LocalStrings provides strings) {
        if (navigation.destination == AppDestination.PATCH) {
            SecondaryScreen(navigation) {
                PatchArea(patch = actions, navigation = navigation, sessionKey = sessionKey, files = files)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().testTag(AppDestinationTags.of(navigation.destination)))
        }
    }
}
