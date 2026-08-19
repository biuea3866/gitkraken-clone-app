package dev.undine.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

private const val WINDOW_TITLE = "Undine"

/**
 * 빈 창 하나를 여는 최소 진입점. 후행 UI 티켓이 붙을 자리이자 패키징 티켓이 포장할 대상이며,
 * 최종 형태는 UND-26 이 소유한다.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    MaterialTheme {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = WINDOW_TITLE) {
        App()
    }
}
