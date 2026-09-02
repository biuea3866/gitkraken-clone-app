package dev.undine.presentation.a11y

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.undine.di.AppComponent
import dev.undine.domain.RepositoryPath
import dev.undine.presentation.AppDestination
import dev.undine.presentation.AppDestinationTags
import dev.undine.presentation.AppErrorState
import dev.undine.presentation.AppRoot
import dev.undine.presentation.AppWiring
import dev.undine.presentation.welcome.WelcomeTags
import io.kotest.matchers.nulls.shouldNotBeNull
import java.io.File
import java.nio.file.Path

/** 실제 저장소를 열고 그래프까지 읽는 경로라 기본 1초로는 모자란다. */
internal const val WAIT_MILLIS = 30_000L

/**
 * 조립된 앱을 감사 대상으로 띄우는 통로.
 *
 * 접근성 감사는 **조립된 앱**을 봐야 한다 — 화면 하나를 손으로 만든 상태로 띄우면 그 화면이
 * 실제 배선에서 어떤 레이블·포커스를 갖는지는 말하지 않는다. `AppAssemblySpec` 이 쓰는 것과 같은
 * 통로(`AppRoot` + `onAssembled`)를 쓰되, 확대 감사가 밀도를 바꿔 끼울 수 있도록 감싸는 지점을
 * [wrap] 으로 열어 둔다.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.startApp(
    settingsFile: Path,
    wrap: (@Composable (@Composable () -> Unit) -> Unit)? = null,
): AppWiring {
    var assembled: AppWiring? = null
    val component = AppComponent(settingsFile, settingsFile.parent)
    val errors = AppErrorState()
    setContent {
        val root: @Composable () -> Unit = {
            AppRoot(component = component, errors = errors, onAssembled = { assembled = it })
        }
        if (wrap == null) root() else wrap(root)
    }
    waitUntil(timeoutMillis = WAIT_MILLIS) { assembled != null }
    return assembled.shouldNotBeNull()
}

/** 앱이 쓰는 그 경로로 저장소를 열어 최근 목록에 남긴다 — 시작 화면이 이 목록을 그린다. */
internal suspend fun rememberAsRecent(settingsFile: Path, vararg repositories: File) {
    val setup = AppComponent(settingsFile, settingsFile.parent)
    repositories.forEach { repository ->
        setup.welcomeActions.openRepository.execute(RepositoryPath(repository.path))
    }
    setup.closeRepository()
}

/** 사용자가 하는 그대로 최근 목록에서 저장소를 열고, 저장소 화면이 그려질 때까지 기다린다. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openRecent(wiring: AppWiring, repository: File) {
    onNodeWithTag(WelcomeTags.recentRow(RepositoryPath(repository.path))).performClick()
    wiring.navigation.go(AppDestination.REPOSITORY)
    waitUntil(timeoutMillis = WAIT_MILLIS) {
        onAllNodesWithTag(AppDestinationTags.of(AppDestination.REPOSITORY)).fetchSemanticsNodes().isNotEmpty()
    }
}
