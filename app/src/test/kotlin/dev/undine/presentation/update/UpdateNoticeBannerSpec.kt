package dev.undine.presentation.update

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.update.CheckUpdateUseCase
import dev.undine.application.update.InstallUpdateUseCase
import dev.undine.application.update.UpdateUseCases
import dev.undine.domain.UpdateCheckSettings
import dev.undine.domain.update.AppVersion
import dev.undine.domain.update.AvailableRelease
import dev.undine.domain.update.InstallerLaunchResult
import dev.undine.domain.update.UpdateCheckResult
import dev.undine.domain.update.UpdateDownloadResult
import dev.undine.domain.update.UpdateFailureKind
import dev.undine.domain.update.UpdateGateway
import dev.undine.presentation.design.UndineTheme
import dev.undine.presentation.i18n.UpdateStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import dev.undine.presentation.i18n.update
import dev.undine.testsupport.inlineTestScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

private val RELEASE = AvailableRelease(
    version = AppVersion(2, 1, 0),
    releaseNotes = "그래프 열 폭 고정과 LFS 동반 다운로드",
    assetName = "undine-2.1.0-linux.deb",
    assetUrl = "https://x.invalid/deb",
    checksumsUrl = "https://x.invalid/checksums",
)

private val INSTALLER: Path = Paths.get("update", RELEASE.assetName)

private const val BLOCKED_REASON = "리베이스를 적용하는 중입니다"

private const val OPEN_FAILURE_REASON = "데스크톱 연동이 없습니다"

/** 실제 한국어 문구. 누락 키가 있으면 조회가 키 이름을 돌려줘 눈에 띈다. */
private val TEXTS: UpdateStrings = builtInStringCatalog().stringsFor(Locale.KOREAN, devBuild = false).update
private val ENGLISH_TEXTS: UpdateStrings =
    builtInStringCatalog().stringsFor(Locale.ENGLISH, devBuild = false).update

private class BannerUpdateGateway(
    private val downloadResult: UpdateDownloadResult = UpdateDownloadResult.Verified(INSTALLER),
    private val openFailureReason: String? = null,
) : UpdateGateway {

    val calls = mutableListOf<String>()

    override suspend fun checkForUpdate(): UpdateCheckResult {
        calls += "check"
        return UpdateCheckResult.UpdateAvailable(RELEASE)
    }

    override suspend fun downloadAndVerify(release: AvailableRelease): UpdateDownloadResult {
        calls += "download"
        return downloadResult
    }

    override suspend fun openInstaller(installer: Path): InstallerLaunchResult {
        calls += "open"
        return openFailureReason
            ?.let { reason -> InstallerLaunchResult.OpenFailed(installer, reason) }
            ?: InstallerLaunchResult.Opened(installer)
    }
}

/**
 * 시작 확인 **한 번만** 돌린 홀더.
 *
 * `Dispatchers.Unconfined` 라 `launch` 가 첫 정지 지점까지 그 자리에서 실행된다 — 그 지점이 다음
 * 주기 대기이므로, 확인 한 번이 끝난 상태에서 코루틴을 접는다. 배너 렌더링을 보는 스펙이라 주기는
 * 필요 없고, 대기를 취소로 뚫지 않으므로 `CancellationException` 을 삼키는 자리도 생기지 않는다.
 */
private fun noticeOf(gateway: UpdateGateway, blockedReason: () -> String?): UpdateNoticeState {
    val scope = inlineTestScope()
    val nextCheck = Channel<Unit>(Channel.RENDEZVOUS)
    val state = UpdateNoticeState(
        scope = scope,
        updates = UpdateUseCases(CheckUpdateUseCase(gateway), InstallUpdateUseCase(gateway)),
        installBlockedReason = blockedReason,
        awaitNextCheck = { nextCheck.receive() },
    )
    scope.launch { state.runChecks(UpdateCheckSettings.DEFAULT) }.cancel()
    return state
}

@Composable
private fun Banner(state: UpdateNoticeState, texts: UpdateStrings = TEXTS) {
    UndineTheme {
        UpdateNoticeBanner(state = state, texts = texts)
    }
}

/**
 * 전역 안내 배너의 렌더링과 동의 동작.
 *
 * **업데이트 전용 화면·탭을 만들지 않는다** (결정 D13·D16) — 이 배너는 `AppRoot` 의 Box 에 형제로
 * 얹히는 컴포넌트 하나다. 여기서는 그 컴포넌트가 릴리즈 노트와 동의 동작을 실제로 그리는지 본다.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateNoticeBannerSpec : FunSpec({

    test("새 버전이 있으면 버전과 릴리즈 노트가 안내된다") {
        val state = noticeOf(BannerUpdateGateway()) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.VERSION).assertTextEquals(TEXTS.newVersion("2.1.0"))
            onNodeWithTag(UpdateTags.RELEASE_NOTES).assertTextEquals(RELEASE.releaseNotes)
            onNodeWithTag(UpdateTags.INSTALL).assertTextEquals(TEXTS.install)
        }
    }

    test("안내할 새 버전이 없으면 배너를 그리지 않는다") {
        val state = UpdateNoticeState(
            scope = inlineTestScope(),
            updates = UpdateUseCases(
                CheckUpdateUseCase(BannerUpdateGateway()),
                InstallUpdateUseCase(BannerUpdateGateway()),
            ),
            installBlockedReason = { null },
        )

        runComposeUiTest {
            setContent { Banner(state) }

            onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().shouldBeEmpty()
        }
    }

    test("동의를 누르지 않으면 아무것도 내려받지 않는다") {
        val gateway = BannerUpdateGateway()
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            gateway.calls shouldContainExactly listOf("check")
        }
    }

    test("동의를 누르면 내려받아 검증하고 연다") {
        val gateway = BannerUpdateGateway()
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            gateway.calls shouldContainExactly listOf("check", "download", "open")
            onNodeWithTag(UpdateTags.RESULT).assertTextEquals(TEXTS.installOpened(INSTALLER.toString()))
        }
    }

    test("진행 중인 작업이 있으면 설치를 보류하고 사유를 그 자리에 보여 준다") {
        val gateway = BannerUpdateGateway()
        val state = noticeOf(gateway) { BLOCKED_REASON }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            gateway.calls shouldContainExactly listOf("check")
            onNodeWithTag(UpdateTags.DEFERRED)
                .assertTextEquals("${TEXTS.installDeferred} $BLOCKED_REASON")
            // 보류지 취소가 아니다 — 안내는 그대로 남는다.
            onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().size shouldBe 1
        }
    }

    test("검증에 실패하면 설치하지 않았고 기존 설치본이 그대로라는 사실을 알린다") {
        val gateway = BannerUpdateGateway(UpdateDownloadResult.ChecksumMismatch(RELEASE.assetName))
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            gateway.calls shouldContainExactly listOf("check", "download")
            // 기대값을 getter 로 두면 문구가 어떻게 바뀌어도 통과한다 — 지키려는 계약을 글자로 못박는다.
            onNodeWithTag(UpdateTags.RESULT)
                .assertTextEquals("받은 파일의 무결성 검증에 실패해 설치하지 않았습니다. 기존 설치본은 그대로입니다.")
        }
    }

    test("영어 문구도 삭제를 단정하지 않는다") {
        val gateway = BannerUpdateGateway(UpdateDownloadResult.ChecksumMismatch(RELEASE.assetName))
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state, texts = ENGLISH_TEXTS) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            onNodeWithTag(UpdateTags.RESULT)
                .assertTextEquals("Integrity check failed, so nothing was installed. Your install is untouched.")
        }
    }

    test("열기에만 실패하면 파일 위치를 알려 직접 실행할 수 있게 한다") {
        val gateway = BannerUpdateGateway(openFailureReason = OPEN_FAILURE_REASON)
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            // 검증 실패와 같은 문구로 접지 않는다 (결정 D18) — 파일은 정상이고 실패한 것은 여는 동작이다.
            gateway.calls shouldContainExactly listOf("check", "download", "open")
            onNodeWithTag(UpdateTags.RESULT)
                .assertTextEquals(TEXTS.installOpenFailed(INSTALLER.toString(), OPEN_FAILURE_REASON))
        }
    }

    test("내려받지 못하면 열지 않고 그 사실을 알린다") {
        val failure = UpdateDownloadResult.DownloadFailed(UpdateFailureKind.NETWORK, "연결할 수 없습니다")
        val gateway = BannerUpdateGateway(failure)
        val state = noticeOf(gateway) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.INSTALL).performClick()
            waitForIdle()

            gateway.calls shouldContainExactly listOf("check", "download")
            onNodeWithTag(UpdateTags.RESULT).assertTextEquals(TEXTS.downloadFailed)
        }
    }

    test("나중에를 누르면 안내가 사라진다") {
        val state = noticeOf(BannerUpdateGateway()) { null }

        runComposeUiTest {
            setContent { Banner(state) }

            onNodeWithTag(UpdateTags.DISMISS).performClick()
            waitForIdle()

            onAllNodesWithTag(UpdateTags.BANNER).fetchSemanticsNodes().shouldBeEmpty()
        }
    }
})
