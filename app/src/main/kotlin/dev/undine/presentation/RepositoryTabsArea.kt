package dev.undine.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.undine.di.AppComponent
import dev.undine.domain.UndineException
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.common
import dev.undine.presentation.i18n.strings
import dev.undine.presentation.i18n.tabs
import dev.undine.presentation.tabs.RepositoryTabs
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 셸 위쪽의 탭 영역 — 탭 막대와 닫기 확인.
 *
 * 탭 막대는 전이를 직접 하지 않고 요청만 낸다. 실제 전이는 [RepositorySessionDriver] 가 세션 홀더의
 * 임계 구역 안에서 끝내고, 여기서는 그 실패를 **삼키지 않고** 전역 안내로 올린다 — 탭이 그대로인
 * 이유를 사용자가 알아야 한다.
 */
@Composable
internal fun RepositoryTabsArea(
    sessions: RepositorySessionDriver<AppComponent.RepositoryUndoScope>,
    scope: CoroutineScope,
    errors: AppErrorState,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RepositoryTabs(
            state = sessions.tabs,
            onActivate = { tabId -> scope.reportingFailure(errors) { sessions.activate(tabId) } },
            onCloseRequested = { request -> scope.reportingFailure(errors) { sessions.requestClose(request) } },
        )
        // 확인이 필요한 닫기만 여기 온다. 요청을 조용히 버리지 않는다.
        sessions.pendingClose?.let {
            TabCloseConfirmation(
                onConfirm = { scope.reportingFailure(errors) { sessions.confirmPendingClose() } },
                onDismiss = sessions::dismissPendingClose,
            )
        }
    }
}

/** 진행 중인 원격 작업이 있는 탭을 닫기 전에 받는 확인. */
@Composable
private fun TabCloseConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tokens = UndineTokens.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.surface)
            .padding(UndineTokens.spacing.small),
    ) {
        BasicText(
            text = strings.tabs.closeTabConfirmation,
            style = UndineTokens.typography.body.copy(color = tokens.foregroundPrimary),
        )
        UndineToolbarButton(label = strings.common.ok, onClick = onConfirm)
        UndineToolbarButton(label = strings.common.cancel, onClick = onDismiss)
    }
}

/**
 * 탭 전이를 띄우고 실패를 전역 안내로 올린다.
 *
 * 취소는 잡지 않는다 — 화면이 사라져 코루틴이 끊긴 것은 사용자에게 알릴 실패가 아니다.
 */
internal fun CoroutineScope.reportingFailure(errors: AppErrorState, block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (failure: UndineException) {
            errors.report(failure, logPath = null)
        } catch (failure: IOException) {
            errors.report(failure, logPath = null)
        }
    }
}
