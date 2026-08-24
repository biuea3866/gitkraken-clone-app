package dev.undine.presentation.diff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * diff 뷰어의 화면 상태 홀더 — 지금은 뷰 모드 하나를 소유한다.
 *
 * 모드를 Composable 내부 `remember` 에 두지 않는 이유는 상위(툴바·명령 팔레트)가 같은 모드를
 * 바꿔야 하기 때문이다 (compose-ui 규칙 1, 상태 끌어올리기). 이 홀더는 Gateway·UseCase 를 알지 못한다.
 */
@Stable
class DiffViewerState(viewMode: DiffViewMode = DiffViewMode.UNIFIED) {

    var viewMode: DiffViewMode by mutableStateOf(viewMode)
        private set

    fun showViewMode(mode: DiffViewMode) {
        viewMode = mode
    }

    fun toggleViewMode() {
        viewMode = when (viewMode) {
            DiffViewMode.UNIFIED -> DiffViewMode.SPLIT
            DiffViewMode.SPLIT -> DiffViewMode.UNIFIED
        }
    }
}

/** 컴포지션 수명 동안 유지되는 뷰어 상태. 영속화 대상은 `Settings` 소유 티켓이 정한다. */
@Composable
fun rememberDiffViewerState(viewMode: DiffViewMode = DiffViewMode.UNIFIED): DiffViewerState =
    remember { DiffViewerState(viewMode) }
