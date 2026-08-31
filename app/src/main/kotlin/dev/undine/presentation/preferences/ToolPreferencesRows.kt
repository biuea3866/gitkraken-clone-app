package dev.undine.presentation.preferences

import dev.undine.domain.ExternalTool
import dev.undine.domain.Settings
import dev.undine.domain.SettingsPreference
import dev.undine.presentation.i18n.PreferencesStrings

/** 값과 "찾을 수 없음" 을 한 줄에 잇는 구분자. */
private const val VALUE_NOTE_SEPARATOR = " — "

/**
 * 도구 탭의 네 행 — 외부 diff 도구, 외부 merge 도구, 탭 폭, 고정폭 서체.
 *
 * 넷 다 앱 설정이 값의 주인이라 출처는 모두 앱 설정이다. 두 도구 행은 공통 복원 버튼을 쓰지 않는다 —
 * 되돌릴 수 있는 항목이 `EXTERNAL_TOOLS` 하나뿐이라 한 도구를 되돌리면 다른 도구까지 지운다.
 * 도구별 복원은 각 행의 편집기가 [PreferencesState.restoreDefaultTool] 로 제공한다.
 */
internal fun toolPreferencesRows(
    settings: Settings,
    texts: PreferencesStrings,
    missing: Set<ExternalToolKind>,
): List<PreferencesRow> = ExternalToolKind.entries
    .map { kind -> externalToolRow(kind, settings, texts, kind in missing) }
    .plus(tabWidthRow(settings, texts))
    .plus(monospaceFontRow(settings, texts))

internal fun tabWidthRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(texts.tabWidth, settings.tabWidth.toString(), SettingsPreference.TAB_WIDTH, texts)

/** 값 없음은 **시스템 기본을 따른다**는 뜻이라 빈 값이 아니라 그 문구로 보인다. */
internal fun monospaceFontRow(settings: Settings, texts: PreferencesStrings): PreferencesRow =
    appPreferencesRow(
        label = texts.monospaceFont,
        value = settings.monospaceFontFamily ?: texts.monospaceFontSystem,
        preference = SettingsPreference.MONOSPACE_FONT,
        texts = texts,
    )

internal fun externalToolRow(
    kind: ExternalToolKind,
    settings: Settings,
    texts: PreferencesStrings,
    executableMissing: Boolean,
): PreferencesRow = PreferencesRow(
    label = kind.labelIn(texts),
    value = toolValueText(kind.toolIn(settings), executableMissing, texts),
    source = PreferenceValueSource.APP_SETTINGS,
    sourceLabel = texts.sourceApp,
    restorablePreference = null,
)

/** 행에 보일 도구 값. 찾을 수 없는 실행 파일은 **그 값 옆**에 붙어 무엇을 고쳐야 하는지 드러낸다. */
internal fun toolValueText(
    tool: ExternalTool?,
    executableMissing: Boolean,
    texts: PreferencesStrings,
): String {
    val value = tool?.commandText() ?: texts.toolUnset
    return if (executableMissing) value + VALUE_NOTE_SEPARATOR + texts.executableNotFound else value
}

internal fun ExternalToolKind.labelIn(texts: PreferencesStrings): String = when (this) {
    ExternalToolKind.DIFF -> texts.diffTool
    ExternalToolKind.MERGE -> texts.mergeTool
}

internal fun ExternalToolKind.toolIn(settings: Settings): ExternalTool? = when (this) {
    ExternalToolKind.DIFF -> settings.externalTools.diffTool
    ExternalToolKind.MERGE -> settings.externalTools.mergeTool
}

/** 저장된 도구를 한 줄 명령으로 되돌린다. 인자 배열은 실행 계약 그대로 공백으로 잇는다. */
internal fun ExternalTool.commandText(): String = (listOf(executable) + arguments).joinToString(" ")

/** 입력 칸이 들고 시작할 값. 설정되지 않은 도구는 빈 칸으로 열린다. */
internal fun ExternalTool?.draftText(): String = this?.commandText().orEmpty()
