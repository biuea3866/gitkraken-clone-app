package dev.undine.presentation.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.undine.domain.SettingsPreference
import dev.undine.domain.signing.SigningSettings
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.i18n.preferences
import dev.undine.presentation.i18n.strings

/**
 * 지금 화면에 보이는 값이 어디서 온 것인지. 앱 설정과 git 설정 중 무엇이 이기고 있는지를 가른다.
 *
 * [PENDING] 과 [UNVERIFIED] 는 **모른다는 답**이다 — 아직 읽지 않았거나(PENDING) 읽지 못한
 * (UNVERIFIED) git 설정은 앱 설정을 이기는지 알 수 없으므로, 그것을 "git 에 값 없음" 으로 접어
 * 앱 출처라고 말하지 않는다 (결정 G35 UND-75 2 · G39). 둘을 가르는 이유는 사용자가 할 일이
 * 다르기 때문이다 — 하나는 기다리면 되고, 하나는 설정 파일을 봐야 한다.
 */
enum class PreferenceValueSource {
    APP_SETTINGS,
    GIT_CONFIG,
    PENDING,
    UNVERIFIED,
}

/**
 * 설정 한 줄의 공통 계약 — 라벨·현재 값·실효값 출처·항목별 기본값 복원.
 *
 * 탭은 이 값을 **조립할 뿐** 자기 방식으로 행을 다시 그리지 않는다. 그래야 여섯 탭이 같은
 * 자리에 같은 정보를 보여주고, 출처 표시가 화면마다 달라지지 않는다.
 *
 * @property restorablePreference 이 행이 되돌릴 수 있는 설정 항목. `null` 이면 **읽기 전용**이다 —
 *   git 설정이 실효값인 항목(서명 등)은 앱이 되돌릴 값을 갖고 있지 않다.
 */
@Immutable
data class PreferencesRow(
    val label: String,
    val value: String,
    val source: PreferenceValueSource,
    val sourceLabel: String,
    val restorablePreference: SettingsPreference?,
) {
    /** 항목별 "기본값으로" 를 내줄 수 있는가. */
    val canRestoreDefault: Boolean get() = restorablePreference != null
}

/** 앱 설정이 실효값인 행. 항목별 기본값 복원을 함께 내준다. */
fun appPreferencesRow(
    label: String,
    value: String,
    preference: SettingsPreference,
    texts: PreferencesStrings,
): PreferencesRow = PreferencesRow(
    label = label,
    value = value,
    source = PreferenceValueSource.APP_SETTINGS,
    sourceLabel = texts.sourceApp,
    restorablePreference = preference,
)

/**
 * git 설정이 이기는 행. 앱은 값을 갖고 있지 않으므로 **보여주기만** 한다 —
 * 앱에 사본을 만들면 사용자가 `git config` 로 바꾼 값과 어긋난다.
 */
fun gitConfigPreferencesRow(
    label: String,
    value: String,
    texts: PreferencesStrings,
): PreferencesRow = PreferencesRow(
    label = label,
    value = value,
    source = PreferenceValueSource.GIT_CONFIG,
    sourceLabel = texts.sourceGit,
    restorablePreference = null,
)

/**
 * 커밋 서명의 실효값 행. 전부 읽기 전용이고 출처는 git 설정이다.
 *
 * 서명 설정을 읽을 수 없으면(저장소가 열려 있지 않음) 행을 만들지 않는다 — 값을 모르는 채
 * "꺼짐" 으로 보여주면 사용자는 서명이 꺼진 줄 안다.
 */
fun signingPreferencesRows(
    signing: SigningSettings?,
    texts: PreferencesStrings,
): List<PreferencesRow> = signing?.let {
    listOf(
        gitConfigPreferencesRow(texts.signCommits, it.signCommits.asOnOff(texts), texts),
        gitConfigPreferencesRow(texts.signTags, it.signTags.asOnOff(texts), texts),
        gitConfigPreferencesRow(texts.signingFormat, it.format.name, texts),
        gitConfigPreferencesRow(texts.signingKey, it.signingKey ?: texts.signingKeyUnset, texts),
    )
} ?: emptyList()

/** on/off 값의 표시 문구. 탭이 각자 "예/아니오" 를 만들지 않게 한 자리에 둔다. */
fun Boolean.asOnOff(texts: PreferencesStrings): String = if (this) texts.enabled else texts.disabled

/**
 * 설정 한 줄을 그린다. 값 편집기는 탭이 붙이고, 이 행은 라벨·값·출처·복원 버튼의 자리를 정한다.
 *
 * @param onRestoreDefault 항목별 기본값 복원. 읽기 전용 행에서는 버튼 자체가 나오지 않는다.
 */
@Composable
fun PreferencesRowItem(
    row: PreferencesRow,
    onRestoreDefault: (SettingsPreference) -> Unit,
    modifier: Modifier = Modifier,
    editor: @Composable () -> Unit = {},
) {
    val colors = UndineTokens.color
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.small)
            .testTag(PreferencesTags.ROW),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
            BasicText(text = row.label, style = typography.body.copy(color = colors.foregroundPrimary))
            BasicText(text = row.value, style = typography.caption.copy(color = colors.foregroundSecondary))
            BasicText(
                text = row.sourceLabel,
                style = typography.caption.copy(color = colors.foregroundTertiary),
                modifier = Modifier
                    .testTag(PreferencesTags.ROW_SOURCE)
                    .semantics { contentDescription = row.sourceLabel },
            )
        }
        editor()
        row.restorablePreference?.let { preference ->
            UndineToolbarButton(
                label = strings.preferences.restoreDefault,
                onClick = { onRestoreDefault(preference) },
                modifier = Modifier.testTag(PreferencesTags.ROW_RESTORE_DEFAULT),
            )
        }
    }
}
