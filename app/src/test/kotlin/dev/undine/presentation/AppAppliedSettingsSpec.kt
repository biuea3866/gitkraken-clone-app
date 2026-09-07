package dev.undine.presentation

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.undine.application.preferences.LoadPreferencesUseCase
import dev.undine.di.AppComponent
import dev.undine.domain.Settings
import dev.undine.domain.SettingsGateway
import dev.undine.domain.ThemeMode
import dev.undine.infrastructure.settings.SettingsGatewayImpl
import dev.undine.presentation.design.UndineTokenSet
import dev.undine.presentation.i18n.withDefaultLocale
import dev.undine.presentation.welcome.WelcomeTags
import io.kotest.core.TestConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.Locale

/** 설정 파일을 실제로 읽어 화면까지 오는 경로라 기본 1초로는 모자란다. */
private const val WAIT_MILLIS = 30_000L

/**
 * 문구 단언은 **고정 카탈로그의 값**으로 한다 — `systemStrings()` 에 기대면 기계의 로케일에 따라
 * 결과가 갈린다 (#125).
 */
private const val KOREAN_TITLE = "저장소 열기"
private const val ENGLISH_TITLE = "Open a repository"

/**
 * 저장된 언어·테마가 **조립된 앱에 실제로 닿는지** 본다.
 *
 * 제공자에 값을 넣는 함수만 따로 부르는 테스트는 그 함수가 맞다는 것만 말한다 — 앱이 저장된 값을
 * 읽어 `LocalStrings` 와 `UndineTheme` 에 넘기는지는 말하지 않는다. 여기서는 `AppRoot` 를 실제
 * `AppComponent` 로 띄워 확인한다 (`testing` 규칙 1 — Mock 을 쓰지 않는다).
 */
@OptIn(ExperimentalTestApi::class)
class AppAppliedSettingsSpec : FunSpec({

    test("저장된 언어가 한국어면 화면 문구가 한국어로 그려진다") {
        val settingsFile = seedSettings { it.copy(language = "ko") }

        // 시스템 로케일이 영어여도 저장된 한국어가 이긴다 — 저장값이 실제로 쓰인다는 뜻이다.
        withDefaultLocale(Locale.ENGLISH) {
            runComposeUiTest {
                startApp(settingsFile)
                awaitTitle(KOREAN_TITLE)
                onNodeWithText(ENGLISH_TITLE).assertDoesNotExist()
            }
        }
    }

    test("저장된 언어가 영어면 같은 화면이 영어로 그려진다") {
        val settingsFile = seedSettings { it.copy(language = "en") }

        withDefaultLocale(Locale.KOREAN) {
            runComposeUiTest {
                startApp(settingsFile)
                awaitTitle(ENGLISH_TITLE)
                onNodeWithText(KOREAN_TITLE).assertDoesNotExist()
            }
        }
    }

    test("언어가 null 이면 시스템 로케일을 따른다") {
        val settingsFile = seedSettings { it.copy(language = null) }

        withDefaultLocale(Locale.ENGLISH) {
            runComposeUiTest {
                startApp(settingsFile)
                awaitTitle(ENGLISH_TITLE)
            }
        }
    }

    test("카탈로그가 모르는 언어 태그는 폴백 로케일로 그려지고 문구가 비지 않는다") {
        val settingsFile = seedSettings { it.copy(language = "fr-FR") }

        withDefaultLocale(Locale.ENGLISH) {
            runComposeUiTest {
                startApp(settingsFile)
                // 폴백 로케일은 한국어다 — 시스템 로케일(영어)이 아니라 카탈로그가 정한다.
                awaitTitle(KOREAN_TITLE)
            }
        }
    }

    test("저장된 테마가 라이트면 라이트로 그려진다") {
        val settingsFile = seedSettings { it.copy(theme = ThemeMode.LIGHT, language = "ko") }

        runComposeUiTest {
            startApp(settingsFile)
            awaitTitle(KOREAN_TITLE)

            backgroundOfWelcome() shouldBe UndineTokenSet.Light.color.background
        }
    }

    test("저장된 테마가 다크면 다크로 그려진다") {
        val settingsFile = seedSettings { it.copy(theme = ThemeMode.DARK, language = "ko") }

        runComposeUiTest {
            startApp(settingsFile)
            awaitTitle(KOREAN_TITLE)

            backgroundOfWelcome() shouldBe UndineTokenSet.Dark.color.background
        }
    }

    test("설정에서 언어와 테마를 바꾸면 재기동 없이 같은 화면이 바뀐다") {
        val settingsFile = seedSettings { it.copy(language = "ko", theme = ThemeMode.DARK) }

        runComposeUiTest {
            val component = startApp(settingsFile)
            awaitTitle(KOREAN_TITLE)
            backgroundOfWelcome() shouldBe UndineTokenSet.Dark.color.background

            // 설정 화면이 쓰는 그 경로다 — 앱이 조립한 홀더에 발행되어 같은 컴포지션이 갱신된다.
            runBlocking {
                component.updatePreferences.execute { it.copy(language = "en", theme = ThemeMode.LIGHT) }
            }

            awaitTitle(ENGLISH_TITLE)
            backgroundOfWelcome() shouldBe UndineTokenSet.Light.color.background
        }
    }

    test("설정을 아직 읽지 못한 첫 프레임은 시스템 로케일과 다크로 그려지고 읽은 뒤 한 번 바뀐다") {
        // 저장된 값은 한국어·라이트다 — 첫 프레임이 그것과 다르게 그려져야 "읽기 전" 이 확인된다.
        val settingsFile = seedSettings { it.copy(language = "ko", theme = ThemeMode.LIGHT) }
        val gateway = GatedSettingsGateway(settingsFile)

        withDefaultLocale(Locale.ENGLISH) {
            runComposeUiTest {
                val component = startApp(settingsFile, gateway)

                // 읽기를 붙들고 있는 동안: 적용값이 **비어 있고** 화면은 시스템 로케일(영어)·다크다.
                // 여기서 기본 Settings 가 실려 있으면 전환이 두 번 일어난다 — 로딩 화면을 두지
                // 않기로 한 판단이 성립하려면 이 구간이 비어 있어야 한다.
                awaitTitle(ENGLISH_TITLE)
                component.appliedSettings.current.value shouldBe null
                backgroundOfWelcome() shouldBe UndineTokenSet.Dark.color.background

                gateway.release()

                // 읽은 뒤 저장값으로 한 번 바뀐다.
                awaitTitle(KOREAN_TITLE)
                backgroundOfWelcome() shouldBe UndineTokenSet.Light.color.background
                component.appliedSettings.current.value?.language shouldBe "ko"
            }
        }
    }

    test("시작 읽기가 실패해도 화면은 뜨고 기본값으로 그려지며 실패를 알린다") {
        val settingsFile = seedSettings { it.copy(language = "ko", theme = ThemeMode.LIGHT) }
        val errors = AppErrorState()

        withDefaultLocale(Locale.ENGLISH) {
            runComposeUiTest {
                val component = startApp(settingsFile, UnreadableSettingsGateway(settingsFile), errors)

                // 저장값(한국어·라이트)에 닿지 못했으니 시스템 로케일(영어)·다크가 그대로 남는다.
                awaitTitle(ENGLISH_TITLE)
                backgroundOfWelcome() shouldBe UndineTokenSet.Dark.color.background
                component.appliedSettings.current.value shouldBe null

                // 조용히 넘기지 않는다 — "기본값이다" 와 "못 읽었다" 는 사용자에게 다른 사실이다.
                waitUntil(timeoutMillis = WAIT_MILLIS) { errors.failure != null }
                errors.failure?.kind shouldBe "IOException"
                errors.failure?.logPath shouldBe null
            }
        }
    }
})

/** 앱을 띄우기 전에 설정 파일에 값을 심는다 — 배선이 **파일에서** 읽는지 보려면 파일에 있어야 한다. */
private fun TestConfiguration.seedSettings(change: (Settings) -> Settings): Path {
    val settingsFile = File(tempdir(), "settings.json").toPath()
    runBlocking { AppComponent(settingsFile, settingsFile.parent).updatePreferences.execute(change) }
    return settingsFile
}

/**
 * 조립을 띄우고 **그 조립이 쓰는 컴포넌트**를 돌려준다. 밖에서 새로 만들면 갱신이 다른 홀더로 가
 * 검증 대상이 앱이 아니라 그 복제본이 된다.
 *
 * @param gateway 설정 영속화를 대신 쥘 구현. `null` 이면 실제 구현이 [settingsFile] 을 읽는다.
 *   실제 구현은 읽기 실패를 기본값으로 접어 예외를 올리지 않으므로, **읽기가 늦거나 실패하는**
 *   시작 경로는 여기에 다른 구현을 넣어야만 태울 수 있다.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.startApp(
    settingsFile: Path,
    gateway: SettingsGateway? = null,
    errors: AppErrorState = AppErrorState(),
): AppComponent {
    val component = gateway
        ?.let { AppComponent(settingsFile, settingsFile.parent, it) }
        ?: AppComponent(settingsFile, settingsFile.parent)
    var assembled: AppWiring? = null
    setContent {
        AppRoot(component = component, errors = errors, onAssembled = { assembled = it })
    }
    waitUntil(timeoutMillis = WAIT_MILLIS) { assembled != null }
    assembled.shouldNotBeNull()
    return component
}

/**
 * 시작 읽기를 시험이 붙들고 있는 Gateway. [release] 전까지 [load] 가 끝나지 않아 **설정을 아직
 * 읽지 못한 상태**를 그대로 잡아 둘 수 있다. 쓰기는 실제 구현에 그대로 넘긴다.
 */
private class GatedSettingsGateway(settingsFile: Path) : SettingsGateway {

    private val delegate = SettingsGatewayImpl(settingsFile)
    private val gate = CompletableDeferred<Unit>()

    override suspend fun load(): Settings {
        gate.await()
        return delegate.load()
    }

    override suspend fun save(settings: Settings) = delegate.save(settings)

    override suspend fun update(transform: (Settings) -> Settings) = delegate.update(transform)

    /** 붙들고 있던 읽기를 놓아 준다. */
    fun release() {
        gate.complete(Unit)
    }
}

/**
 * **시작 설정 읽기만** 실패하는 Gateway. 최근 저장소 목록도 같은 [SettingsGateway] 를 쓰므로 모든
 * 읽기를 실패시키면 이 티켓과 무관한 경로까지 함께 무너져, 무엇을 검증했는지 알 수 없게 된다.
 *
 * 실제 구현은 읽기 실패를 기본값으로 접어 예외를 올리지 않으므로([SettingsGatewayImpl]) 이 경로는
 * 여기서만 만들 수 있다.
 */
private class UnreadableSettingsGateway(settingsFile: Path) : SettingsGateway {

    private val delegate = SettingsGatewayImpl(settingsFile)

    override suspend fun load(): Settings {
        if (readingPreferences()) throw IOException("설정을 읽을 수 없습니다")
        return delegate.load()
    }

    override suspend fun save(settings: Settings) = delegate.save(settings)

    override suspend fun update(transform: (Settings) -> Settings) = delegate.update(transform)

    /** 호출 스택에 설정 읽기 UseCase 가 있으면 시작 읽기다 — 호출 순서에 기대지 않고 가른다. */
    private fun readingPreferences(): Boolean =
        Throwable().stackTrace.any { it.className == LoadPreferencesUseCase::class.java.name }
}

/** 설정 읽기는 비동기라 전환을 기다린다 — 첫 프레임은 아직 적용 전 값이다. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitTitle(title: String) {
    waitUntil(timeoutMillis = WAIT_MILLIS) {
        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
    }
}

/** Welcome 의 배경 픽셀 — 색이 토큰에서 오는지 확인하는 직접적인 방법이다 (`AppShellSpec` 선례). */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.backgroundOfWelcome() =
    onNodeWithTag(WelcomeTags.ROOT).captureToImage().toPixelMap().let { pixels ->
        pixels[1, pixels.height - 2]
    }
