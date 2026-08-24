package dev.undine.presentation.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.undine.domain.ThemeMode
import dev.undine.presentation.i18n.LocalStrings
import dev.undine.presentation.i18n.builtInStringCatalog
import java.io.File
import java.util.Locale

/**
 * 컴포저블을 PNG 로 렌더한다. **시각 확인 수단**이다 — 데스크톱 창을 띄우고 화면을 캡처하는 방식은
 * 창이 다른 디스플레이·Space 에 있으면 실패하고, 무엇보다 사람이 눈으로 봐야만 확인된다.
 *
 * 색·행 밀도 같은 시각 결정은 "빌드가 통과한다" 로 검증되지 않는다. 이 렌더러는 그 결정을
 * **파일로 남겨** 사람이 보고 판단할 수 있게 한다. 테스트 스코프에 두므로 앱 산출물에는 들어가지 않는다.
 *
 * 출력은 `app/build/screenshots/` 다 — 빌드 산출물이라 커밋되지 않는다.
 */
object ScreenshotRenderer {

    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 800

    /** 무효화가 가라앉기를 기다리는 최대 프레임 수. */
    private const val MAX_SETTLE_FRAMES = 20

    /** 렌더 결과를 둘 디렉터리. 빌드 산출물이라 gitignore 대상이다. */
    val outputDirectory: File = File("build/screenshots")

    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        name: String,
        themeMode: ThemeMode = ThemeMode.DARK,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        content: @Composable () -> Unit,
    ): File {
        outputDirectory.mkdirs()
        val target = File(outputDirectory, "$name.png")
        val strings = builtInStringCatalog().stringsFor(Locale.KOREAN, devBuild = false)
        // ImageComposeScene 은 AutoCloseable 이 아니라 close() 만 있다 — use {} 를 쓸 수 없다.
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            UndineTheme(themeMode = themeMode, isSystemInDarkMode = { true }) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(UndineTokens.color.background),
                    ) {
                        content()
                    }
                }
            }
        }
        try {
            // 첫 프레임에서는 LaunchedEffect 가 아직 결과를 못 세웠을 수 있다 — 무효화가 남아 있는
            // 동안 프레임을 더 진행한다. 상한을 두어 무한 무효화(애니메이션)에서 멈추지 않게 한다.
            var image = scene.render()
            var frames = 0
            while (scene.hasInvalidations() && frames < MAX_SETTLE_FRAMES) {
                image = scene.render()
                frames++
            }
            val encoded = image.encodeToData() ?: error("렌더 결과를 PNG 로 인코딩하지 못했습니다: $name")
            target.writeBytes(encoded.bytes)
        } finally {
            scene.close()
        }
        return target
    }
}
