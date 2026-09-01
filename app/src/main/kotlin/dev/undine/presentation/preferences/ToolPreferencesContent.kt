package dev.undine.presentation.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.application.externaltool.ExternalToolUseCases
import dev.undine.application.typography.LoadMonospaceFontsUseCase
import dev.undine.domain.Settings
import dev.undine.presentation.design.UndineTokens
import dev.undine.presentation.design.component.UndineToolbarButton
import dev.undine.presentation.i18n.PreferencesStrings

private val FIELD_WIDTH = 240.dp

/** 도구 탭이 편집하는 외부 도구. 두 도구의 저장 경로가 같아 갈래를 한 자리에 둔다. */
enum class ExternalToolKind {
    DIFF,
    MERGE,
}

/** 화면 요소를 가리키는 도구 탭 전용 테스트 태그. 공통 태그는 [PreferencesTags] 가 소유한다. */
object ToolPreferencesTags {
    const val DIFF_COMMAND: String = "preferences.tools.diffCommand"
    const val MERGE_COMMAND: String = "preferences.tools.mergeCommand"
    const val TOOL_RESTORE_DEFAULT: String = "preferences.tools.restoreDefault"
    const val TAB_WIDTH: String = "preferences.tools.tabWidth"
    const val MONOSPACE_FONT: String = "preferences.tools.monospaceFont"

    /** 고정폭 서체 후보 하나를 고르는 버튼. 후보가 없으면 이 태그의 요소도 없다. */
    const val MONOSPACE_FONT_CHOICE: String = "preferences.tools.monospaceFont.choice"
}

/**
 * 도구 탭 — 외부 diff/merge 도구와 사용자 지정 명령, 탭 폭·고정폭 서체.
 *
 * **앱 설정 값만 다룬다.** 저장소의 `diff.tool`·`merge.tool` 이 실제 실행에서 앱 설정을 이기지만,
 * 그 실효값과 출처를 읽는 계약은 아직 없다 — 표시는 UND-75 가 소유한다. 여기서 실효값을 흉내 내면
 * 사용자가 `git config` 로 바꾼 값과 어긋난 사본이 생긴다.
 *
 * **저장 버튼이 없다.** 편집은 [PreferencesState.apply] 로 곧바로 저장되고, 화면 값은 저장 결과로만
 * 갱신된다. 값이 거부되거나 쓰기가 실패하면 행의 값은 저장된 값에 머물고 사유는 화면 상단의
 * 공통 표시가 맡는다 ([PreferencesSaveFailure]).
 *
 * **실행 파일을 찾지 못해도 저장을 막지 않는다** — 아직 설치 전인 도구를 미리 설정해 둘 수 있어야
 * 한다. 찾을 수 없다는 사실은 사용자가 고칠 수 있는 자리, 곧 **앱 설정 명령 값 옆**에 붙인다.
 *
 * **고정폭 서체는 후보에서 고를 수도, 직접 적을 수도 있다.** 후보는 고르기를 돕는 것일 뿐이라
 * 열거가 실패해도 입력 칸이 사라지지 않고, 후보에 없는 저장값도 선택지에 남는다
 * ([monospaceFontChoices]).
 *
 * @param monospaceFonts 설치된 고정폭 서체 열거. 화면은 UseCase 만 부르고 Gateway 를 알지 못한다.
 */
@Composable
fun ToolPreferencesContent(
    state: PreferencesState,
    texts: PreferencesStrings,
    externalTools: ExternalToolUseCases,
    monospaceFonts: LoadMonospaceFontsUseCase,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val missing = rememberMissingToolExecutables(settings, externalTools.checkAvailability)
    val fonts = rememberMonospaceFontState(monospaceFonts)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(UndineTokens.spacing.small),
    ) {
        ExternalToolKind.entries.forEach { kind ->
            val row = externalToolRow(kind, settings, texts, kind in missing)
            PreferencesRowItem(row = row, onRestoreDefault = state::restoreDefault) {
                ExternalToolEditor(kind, state, texts)
            }
        }
        PreferencesRowItem(row = tabWidthRow(settings, texts), onRestoreDefault = state::restoreDefault) {
            PreferenceTextEditor(
                value = settings.tabWidth.toString(),
                label = texts.tabWidth,
                tag = ToolPreferencesTags.TAB_WIDTH,
                onCommit = state::applyTabWidth,
            )
        }
        PreferencesRowItem(row = monospaceFontRow(settings, texts), onRestoreDefault = state::restoreDefault) {
            // 직접 입력은 후보가 있든 없든 그대로 남는다 — 후보는 고르기를 돕는 것일 뿐이다.
            PreferenceTextEditor(
                value = settings.monospaceFontFamily.orEmpty(),
                label = texts.monospaceFont,
                tag = ToolPreferencesTags.MONOSPACE_FONT,
                onCommit = state::applyMonospaceFont,
            )
            MonospaceFontChoices(
                choices = monospaceFontChoices(fonts.candidates, settings.monospaceFontFamily),
                onChoose = state::applyMonospaceFont,
            )
        }
    }
}

/** 후보 서체를 고르는 자리. 후보가 하나도 없으면 아무것도 그리지 않는다 — 빈 틀을 두지 않는다. */
@Composable
private fun MonospaceFontChoices(choices: List<String>, onChoose: (String) -> Unit) {
    if (choices.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(UndineTokens.spacing.extraSmall)) {
        choices.forEach { family ->
            UndineToolbarButton(
                label = family,
                onClick = { onChoose(family) },
                modifier = Modifier.testTag(ToolPreferencesTags.MONOSPACE_FONT_CHOICE),
            )
        }
    }
}

/**
 * 실행 파일 존재 확인. 저장된 도구가 바뀔 때만 다시 본다.
 *
 * 확인 전 기본값은 **찾음** 이다 — 아직 모르는 것을 "없음" 으로 보여 주면 설치돼 있는 도구가
 * 잠깐 오류로 보인다. 화면 이탈로 확인이 취소되면 표시가 갱신되지 않을 뿐 저장에는 영향이 없다.
 */
@Composable
private fun rememberMissingToolExecutables(
    settings: Settings,
    checkAvailability: CheckToolAvailabilityUseCase,
): Set<ExternalToolKind> {
    var missing by remember { mutableStateOf(emptySet<ExternalToolKind>()) }
    LaunchedEffect(settings.externalTools) {
        missing = missingToolExecutables(settings, checkAvailability)
    }
    return missing
}

/** 도구 한 개의 편집기 — 사용자 지정 명령 입력과 그 도구만의 기본값 복원. */
@Composable
private fun ExternalToolEditor(
    kind: ExternalToolKind,
    state: PreferencesState,
    texts: PreferencesStrings,
) {
    PreferenceTextEditor(
        value = kind.toolIn(state.settings).draftText(),
        label = texts.customToolCommand,
        tag = kind.commandTag(),
        onCommit = { command -> state.applyToolCommand(kind, command) },
    )
    UndineToolbarButton(
        label = texts.restoreDefault,
        onClick = { state.restoreDefaultTool(kind) },
        modifier = Modifier.testTag(ToolPreferencesTags.TOOL_RESTORE_DEFAULT),
    )
}

private fun ExternalToolKind.commandTag(): String = when (this) {
    ExternalToolKind.DIFF -> ToolPreferencesTags.DIFF_COMMAND
    ExternalToolKind.MERGE -> ToolPreferencesTags.MERGE_COMMAND
}

/**
 * 값 하나를 고치는 입력 칸.
 *
 * 초안은 이 칸의 편집 상태라 여기서 기억하고, **저장된 값이 바뀌면 그 값으로 다시 시작한다** —
 * 화면이 보여 주는 값은 언제나 저장된 값이다(행의 값). 확정은 Enter(키보드)와 포커스 이탈(마우스)
 * 두 경로가 같은 [commitDraft] 로 들어간다 — 타자 한 글자마다 저장하면 지우고 다시 적는 중간
 * 입력까지 저장 대상이 된다.
 */
@Composable
private fun PreferenceTextEditor(
    value: String,
    label: String,
    tag: String,
    onCommit: (String) -> Unit,
) {
    val colors = UndineTokens.color
    var draft by remember(value) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val commit = { commitDraft(draft = draft.text, saved = value, onCommit = onCommit) }

    BasicTextField(
        value = draft,
        onValueChange = { edited -> draft = edited },
        modifier = Modifier
            .width(FIELD_WIDTH)
            .background(colors.background)
            .border(UndineTokens.shape.borderThin, colors.border)
            .onFocusChanged { focus -> if (!focus.isFocused) commit() }
            .onPreviewKeyEvent { event ->
                isToolEditorCommitKey(event.key, event.type).also { commits -> if (commits) commit() }
            }
            .semantics { contentDescription = label }
            .testTag(tag),
        textStyle = UndineTokens.typography.body.copy(color = colors.foregroundPrimary),
        cursorBrush = SolidColor(colors.foregroundPrimary),
        singleLine = true,
    )
}
