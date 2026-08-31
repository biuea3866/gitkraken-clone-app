package dev.undine.presentation.preferences

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import dev.undine.domain.SettingsPreference
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.i18n.PreferencesStrings

/** 숫자 입력칸의 폭. 값 자릿수가 달라도 두 행의 입력칸이 같은 자리에서 시작하게 고정한다. */
private val NUMERIC_FIELD_WIDTH = 160.dp

/**
 * 고급 탭 — 대용량 파일 임계치와 이력을 한 번에 읽을 개수.
 *
 * **저장 버튼이 없다.** 숫자로 읽히는 입력은 곧바로 [PreferencesState.apply] 로 저장을 요청하고,
 * 화면에 보이는 값은 저장·읽기 결과([PreferencesState.settings])로만 갱신된다 — 저장되지 않은 값을
 * 미리 그리면 사용자는 반영된 줄 안다.
 *
 * **허용 범위는 여기서 판정하지 않는다.** 0 이하 같은 범위 밖 값은 domain `Settings` 의 `require`
 * 가 저장 전에 거부하고, 그 거부는 공용 저장 실패 자리에 뜬다. 탭마다 범위를 다시 적으면 한 곳만
 * 틀려도 조용히 통과한다. 이 탭이 스스로 거르는 것은 **숫자로 읽히지 않는 입력** 하나뿐이다 —
 * 저장을 부를 값 자체가 없는 경우다.
 *
 * 로그 위치 표시·폴더 열기는 파일시스템 계약이 서는 UND-78 이 채운다. 저장된 값을 `DiffLimits`·
 * `GraphViewState` 같은 소비 경로에 잇는 것도 이 탭의 일이 아니다.
 *
 * 시그니처 고정 이유는 [GeneralPreferencesContent] 와 같다. 고급 탭은 추가 의존이 없다.
 */
@Composable
fun AdvancedPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        NumericPreferenceRow(
            row = appPreferencesRow(
                label = texts.largeFileThreshold,
                value = state.settings.largeFileThresholdBytes.toString(),
                preference = SettingsPreference.LARGE_FILE_THRESHOLD,
                texts = texts,
            ),
            fieldTag = AdvancedPreferencesTags.LARGE_FILE_THRESHOLD,
            texts = texts,
            onRestoreDefault = state::restoreDefault,
            onEdited = { text ->
                val parsed = text.toLongOrNull()
                if (parsed != null) state.apply { it.copy(largeFileThresholdBytes = parsed) }
                parsed != null
            },
        )
        NumericPreferenceRow(
            row = appPreferencesRow(
                label = texts.commitPageSize,
                value = state.settings.commitPageSize.toString(),
                preference = SettingsPreference.COMMIT_PAGE_SIZE,
                texts = texts,
            ),
            fieldTag = AdvancedPreferencesTags.COMMIT_PAGE_SIZE,
            texts = texts,
            onRestoreDefault = state::restoreDefault,
            onEdited = { text ->
                val parsed = text.toIntOrNull()
                if (parsed != null) state.apply { it.copy(commitPageSize = parsed) }
                parsed != null
            },
        )
    }
}

/**
 * 숫자 하나를 편집하는 설정 행.
 *
 * **입력칸이 보여주는 것은 저장된 값이다.** 숫자로 읽힌 입력은 이 자리가 들고 있지 않으므로 저장이
 * 성공해 [PreferencesRow.value] 가 바뀔 때에만 화면이 따라간다. 거부(범위 밖)나 쓰기 실패 뒤에는
 * 그 값이 바뀌지 않아 입력칸이 저장된 값에 그대로 머문다 — 되돌릴 대상이 애초에 생기지 않는다.
 * 저장 실패를 관찰해 되돌리는 방식은 실패가 같은 사유로 이어질 때 되돌릴 계기를 잃는다.
 *
 * **숫자로 읽히지 않은 입력만 이 자리가 들고 있다.** 그 입력은 저장을 부르지도 못해 대응하는 저장
 * 값이 없고, 사용자가 고칠 수 있으려면 화면에 남아야 한다 (compose-ui 규칙 1 의 "비즈니스 상태" 가
 * 아니다). 항목별 기본값 복원·전체 초기화처럼 저장 값이 바뀌면 [remember] 키가 달라져 지워진다.
 *
 * @param onEdited 입력을 숫자로 읽어 저장을 요청했으면 `true`. 숫자가 아니면 저장을 부르지 않고
 *   `false` 를 돌려주며, 이 행이 입력 오류를 표시한다.
 */
@Composable
private fun NumericPreferenceRow(
    row: PreferencesRow,
    fieldTag: String,
    texts: PreferencesStrings,
    onRestoreDefault: (SettingsPreference) -> Unit,
    onEdited: (String) -> Boolean,
) {
    val colors = UndineTokens.color
    val shape = UndineTokens.shape
    val spacing = UndineTokens.spacing
    val typography = UndineTokens.typography

    var unparsedInput by remember(row.value) { mutableStateOf<String?>(null) }

    Column {
        PreferencesRowItem(row = row, onRestoreDefault = onRestoreDefault) {
            BasicTextField(
                value = unparsedInput ?: row.value,
                onValueChange = { text ->
                    val submitted = onEdited(text)
                    unparsedInput = if (submitted) null else text
                },
                singleLine = true,
                textStyle = typography.body.copy(color = colors.foregroundPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .width(NUMERIC_FIELD_WIDTH)
                    .border(
                        width = shape.borderThin,
                        color = if (unparsedInput != null) colors.warning else colors.border,
                        shape = RoundedCornerShape(shape.cornerSmall),
                    )
                    .padding(horizontal = spacing.small, vertical = spacing.extraSmall)
                    .testTag(fieldTag),
            )
        }
        if (unparsedInput != null) {
            BasicText(
                text = texts.invalidValue,
                style = typography.caption.copy(color = colors.warning),
                modifier = Modifier.testTag(AdvancedPreferencesTags.inputErrorOf(fieldTag)),
            )
        }
    }
}
