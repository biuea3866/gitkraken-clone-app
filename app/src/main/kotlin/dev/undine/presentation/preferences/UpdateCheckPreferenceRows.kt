package dev.undine.presentation.preferences

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.domain.UpdateCheckSettings
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings
import dev.undine.presentation.i18n.UpdateStrings

/** 주기 입력칸의 폭. 고급 탭의 숫자 입력과 같은 자리 감각을 준다. */
private val INTERVAL_FIELD_WIDTH = 160.dp

/** 자동 업데이트 설정 행이 쓰는 화면 태그. */
object UpdateCheckPreferencesTags {
    const val ENABLED_ON: String = "preferences.update.check.on"
    const val ENABLED_OFF: String = "preferences.update.check.off"
    const val INTERVAL: String = "preferences.update.check.interval"
    const val INTERVAL_ERROR: String = "preferences.update.check.interval.error"
}

/**
 * 일반 탭의 자동 업데이트 확인 행 두 개 — on/off 와 확인 주기.
 *
 * **일반 탭에 둔다** (결정 D17). 일반이 담는 것은 테마·언어·마지막 저장소 다시 열기처럼 **사용자가
 * 보는 앱 동작**이고, 자동 업데이트 확인도 같은 성격이다. 고급은 로그 디렉터리처럼 진단 성격을 담는다.
 * **업데이트 전용 탭을 만들지 않는다** (결정 D13) — 설정 둘에 탭 하나는 과하다.
 *
 * **설정 스키마를 넓히지 않는다.** `Settings.updateCheck` 의 `enabled`·`intervalHours` 를 읽고 쓸 뿐이다.
 */
@Composable
fun UpdateCheckPreferenceRows(
    state: PreferencesState,
    texts: PreferencesStrings,
    updateTexts: UpdateStrings,
) {
    val settings = state.settings

    PreferencesRowItem(automaticUpdateCheckRow(settings, texts, updateTexts), state::restoreDefault) {
        Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
            UndineToolbarButton(
                label = texts.enabled,
                onClick = { state.apply { it.withUpdateCheckEnabled(true) } },
                enabled = !settings.updateCheck.enabled,
                modifier = Modifier.testTag(UpdateCheckPreferencesTags.ENABLED_ON),
            )
            UndineToolbarButton(
                label = texts.disabled,
                onClick = { state.apply { it.withUpdateCheckEnabled(false) } },
                enabled = settings.updateCheck.enabled,
                modifier = Modifier.testTag(UpdateCheckPreferencesTags.ENABLED_OFF),
            )
        }
    }
    UpdateCheckIntervalRow(state, texts, updateTexts)
}

/**
 * 확인 주기 입력.
 *
 * **입력칸이 보여주는 것은 저장된 값이다** — 고급 탭의 숫자 행과 같은 규약이다 (결정 G12). 뜻이 있는
 * 범위([UpdateCheckSettings.INTERVAL_HOURS_RANGE]) 밖의 값은 저장을 부르지 않고 이 자리가 들고 있다:
 * 저장해 봐야 설정 파일을 읽는 codec 이 기본 주기로 되돌려, 화면과 실제 주기가 갈린다.
 */
@Composable
private fun UpdateCheckIntervalRow(
    state: PreferencesState,
    texts: PreferencesStrings,
    updateTexts: UpdateStrings,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    val row = updateCheckIntervalRow(state.settings, texts, updateTexts)
    var unparsedInput by remember(row.value) { mutableStateOf<String?>(null) }

    Column {
        PreferencesRowItem(row = row, onRestoreDefault = state::restoreDefault) {
            BasicTextField(
                value = unparsedInput ?: row.value,
                onValueChange = { text ->
                    val hours = readIntervalHours(text)
                    if (hours != null) state.apply { it.withUpdateCheckInterval(hours) }
                    unparsedInput = if (hours == null) text else null
                },
                singleLine = true,
                textStyle = typography.body.copy(color = colors.foregroundPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .width(INTERVAL_FIELD_WIDTH)
                    .border(
                        width = shape.borderThin,
                        color = if (unparsedInput != null) colors.warning else colors.border,
                        shape = RoundedCornerShape(shape.cornerSmall),
                    )
                    .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                    // 행의 라벨은 별개 노드다 — 입력창 자신이 이름을 가져야 스크린리더가 읽는다.
                    .semantics { contentDescription = row.label }
                    .testTag(UpdateCheckPreferencesTags.INTERVAL),
            )
        }
        if (unparsedInput != null) {
            BasicText(
                text = texts.invalidValue,
                style = typography.caption.copy(color = colors.warning),
                modifier = Modifier.testTag(UpdateCheckPreferencesTags.INTERVAL_ERROR),
            )
        }
    }
}

/** 자동 확인 on/off 행. */
internal fun automaticUpdateCheckRow(
    settings: Settings,
    texts: PreferencesStrings,
    updateTexts: UpdateStrings,
): PreferencesRow = appPreferencesRow(
    label = updateTexts.automaticCheck,
    value = settings.updateCheck.enabled.asOnOff(texts),
    preference = SettingsPreference.UPDATE_CHECK,
    texts = texts,
)

/** 확인 주기 행. 꺼져 있어도 값은 남긴다 — 다시 켤 때 되찾을 값이다. */
internal fun updateCheckIntervalRow(
    settings: Settings,
    texts: PreferencesStrings,
    updateTexts: UpdateStrings,
): PreferencesRow = appPreferencesRow(
    label = updateTexts.checkInterval,
    value = settings.updateCheck.intervalHours.toString(),
    preference = SettingsPreference.UPDATE_CHECK,
    texts = texts,
)

/**
 * 입력이 뜻 있는 주기인가. 아니면 `null` 이다.
 *
 * 범위는 **domain 이 선언한 것**을 그대로 본다 — 탭이 자기 범위를 새로 정하면 codec 이 읽는 기준과
 * 갈려, 저장은 됐는데 실제 주기는 다른 상태가 된다.
 */
internal fun readIntervalHours(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in UpdateCheckSettings.INTERVAL_HOURS_RANGE }

/** 자동 확인 켬·끔. 주기는 건드리지 않는다 — 다시 켤 때 되찾을 값이다. */
internal fun Settings.withUpdateCheckEnabled(enabled: Boolean): Settings =
    copy(updateCheck = updateCheck.copy(enabled = enabled))

/** 확인 주기만 바꾼다. on/off 는 그대로 둔다. */
internal fun Settings.withUpdateCheckInterval(hours: Int): Settings =
    copy(updateCheck = updateCheck.copy(intervalHours = hours))
