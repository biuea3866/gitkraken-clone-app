package dev.undine.presentation.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import dev.undine.domain.update.UpdateInstallResult
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.UpdateStrings

/**
 * 새 버전 안내와 동의 버튼.
 *
 * **새 화면을 만들지 않는다** (결정 D16) — `AppRoot` 의 `Box` 안에서 `GlobalFailureBanner` 와 같은
 * 조립 지점에 형제로 놓인다. 설정 하나와 안내 하나 때문에 전용 탭·화면을 만들면 앱의 정보 구조가
 * 그만큼 넓어지기만 한다.
 *
 * **릴리즈 노트를 함께 보여 준다** — 무엇이 바뀌는지 모르고 업데이트하게 하지 않는다.
 *
 * 안내할 새 버전이 없으면 아무것도 그리지 않는다. 확인 실패는 여기 오지 않는다 (결정 D6).
 */
@Composable
fun UpdateNoticeBanner(
    state: UpdateNoticeState,
    texts: UpdateStrings,
    modifier: Modifier = Modifier,
) {
    val release = state.availableRelease ?: return
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(spacing.medium)
            .testTag(UpdateTags.BANNER),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        BasicText(
            text = texts.newVersion(release.version.toString()),
            style = typography.body.copy(color = colors.foregroundPrimary),
            modifier = Modifier.testTag(UpdateTags.VERSION),
        )
        BasicText(
            text = texts.releaseNotes,
            style = typography.caption.copy(color = colors.foregroundTertiary),
        )
        BasicText(
            text = release.releaseNotes.ifBlank { texts.releaseNotesEmpty },
            style = typography.caption.copy(color = colors.foregroundSecondary),
            modifier = Modifier.testTag(UpdateTags.RELEASE_NOTES),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            UndineToolbarButton(
                label = if (state.isInstalling) texts.installing else texts.install,
                onClick = state::install,
                enabled = !state.isInstalling,
                modifier = Modifier.testTag(UpdateTags.INSTALL),
            )
            UndineToolbarButton(
                label = texts.later,
                onClick = state::dismiss,
                modifier = Modifier.testTag(UpdateTags.DISMISS),
            )
        }
        state.deferredReason?.let { reason ->
            // 사유는 화면 이동이 쓰는 것과 같은 문자열이다 — 여기서 다시 짓지 않는다 (결정 D11).
            BasicText(
                text = "${texts.installDeferred} $reason",
                style = typography.caption.copy(color = colors.warning),
                modifier = Modifier.testTag(UpdateTags.DEFERRED),
            )
        }
        state.installResult?.let { result ->
            BasicText(
                text = result.messageIn(texts),
                style = typography.caption.copy(color = result.toneOf(colors.warning, colors.foregroundSecondary)),
                modifier = Modifier.testTag(UpdateTags.RESULT),
            )
        }
    }
}

/**
 * 설치 결과 문구.
 *
 * 열기 실패와 검증 실패를 같은 문구로 접지 않는다 — 앞은 **파일 위치를 알려** 직접 실행하게 하고
 * (결정 D18), 뒤는 설치하지 않았고 기존 설치본이 그대로임을 말한다 — **삭제를 단정하지 않는다** (결정 D5).
 */
internal fun UpdateInstallResult.messageIn(texts: UpdateStrings): String = when (this) {
    is UpdateInstallResult.Opened -> texts.installOpened(installer.toString())
    is UpdateInstallResult.OpenFailed -> texts.installOpenFailed(installer.toString(), reason)
    is UpdateInstallResult.ChecksumMismatch -> texts.checksumMismatch
    is UpdateInstallResult.DownloadFailed -> texts.downloadFailed
}

/** 성공은 보조 문구 색, 실패는 경고 색. 색은 토큰에서만 온다. */
private fun UpdateInstallResult.toneOf(warning: Color, normal: Color): Color = when (this) {
    is UpdateInstallResult.Opened -> normal
    is UpdateInstallResult.OpenFailed,
    is UpdateInstallResult.ChecksumMismatch,
    is UpdateInstallResult.DownloadFailed,
    -> warning
}
