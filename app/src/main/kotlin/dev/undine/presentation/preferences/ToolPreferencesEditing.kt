package dev.undine.presentation.preferences

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.undine.application.externaltool.CheckToolAvailabilityUseCase
import dev.undine.domain.ExternalTool
import dev.undine.domain.Settings

/** 명령 한 줄을 인자로 나누는 경계. 셸을 거치지 않는 실행 계약이라 공백 하나가 곧 인자 경계다. */
private val COMMAND_SEPARATOR = Regex("\\s+")

/**
 * 숫자로 읽히지 않는 탭 폭 입력을 넘길 값. domain 이 **명백히 틀린 값**으로 거부하는 0 을 그대로
 * 넘겨, 범위 판정이 탭이 아니라 `Settings` 한 곳에서만 일어나게 한다.
 */
private const val REJECTED_TAB_WIDTH = 0

/**
 * 한 줄 명령을 실행 파일과 인자 배열로 나눈다.
 *
 * 따옴표 규칙을 여기서 발명하지 않는다 — 실행은 셸을 거치지 않는 인자 배열이라(`ExternalToolGateway`)
 * 공백 하나가 곧 인자 경계다. 비어 있으면 `null` 이며, 이는 실패가 아니라 "설정되지 않음" 이다.
 */
internal fun parseToolCommand(command: String): ExternalTool? {
    val parts = command.split(COMMAND_SEPARATOR).filter(String::isNotBlank)
    val executable = parts.firstOrNull() ?: return null
    return ExternalTool(executable = executable, arguments = parts.drop(1))
}

/**
 * 지금 실행 파일을 찾을 수 없는 도구. 설정되지 않은 도구는 확인 대상이 아니다 —
 * 값이 없는 것과 값이 틀린 것은 다르다.
 *
 * **판정이 저장을 막지 않는다.** 결과는 표시에만 쓰인다.
 */
internal suspend fun missingToolExecutables(
    settings: Settings,
    checkAvailability: CheckToolAvailabilityUseCase,
): Set<ExternalToolKind> = ExternalToolKind.entries
    .filter { kind ->
        val executable = kind.toolIn(settings)?.executable ?: return@filter false
        !checkAvailability.execute(executable)
    }
    .toSet()

/** 사용자가 적은 명령을 앱 설정으로 저장한다. 빈 입력은 그 도구를 설정되지 않음으로 되돌린다. */
internal fun PreferencesState.applyToolCommand(kind: ExternalToolKind, command: String) {
    apply { settings -> settings.withTool(kind, parseToolCommand(command)) }
}

/** 도구 하나만 기본값(설정되지 않음)으로 되돌린다. 다른 도구는 그대로 둔다. */
internal fun PreferencesState.restoreDefaultTool(kind: ExternalToolKind) {
    apply { settings -> settings.withTool(kind, null) }
}

/**
 * 탭 폭을 저장한다. **범위 검사를 여기서 하지 않는다** — 숫자로 읽히지 않는 입력도 domain 이
 * 거부하는 값으로 그대로 넘겨, 0 이하 판정과 같은 한 경로로 끝나게 한다 (`Settings` 의 `require`).
 */
internal fun PreferencesState.applyTabWidth(text: String) {
    apply { settings -> settings.copy(tabWidth = text.trim().toIntOrNull() ?: REJECTED_TAB_WIDTH) }
}

/** 고정폭 서체 이름을 저장한다. 빈 입력은 시스템 기본을 따른다는 뜻이라 값 없음으로 저장한다. */
internal fun PreferencesState.applyMonospaceFont(text: String) {
    apply { settings -> settings.copy(monospaceFontFamily = text.trim().ifBlank { null }) }
}

private fun Settings.withTool(kind: ExternalToolKind, tool: ExternalTool?): Settings = when (kind) {
    ExternalToolKind.DIFF -> copy(externalTools = externalTools.copy(diffTool = tool))
    ExternalToolKind.MERGE -> copy(externalTools = externalTools.copy(mergeTool = tool))
}

/** 입력을 확정하는 키인가. 포커스를 옮기는 마우스 경로와 **같은 확정 함수**로 들어간다. */
internal fun isToolEditorCommitKey(key: Key, type: KeyEventType): Boolean =
    type == KeyEventType.KeyDown && (key == Key.Enter || key == Key.NumPadEnter)

/** 초안을 확정한다. 저장된 값과 같으면 저장을 부르지 않는다 — 스쳐 간 포커스가 저장을 돌리지 않는다. */
internal fun commitDraft(draft: String, saved: String, onCommit: (String) -> Unit) {
    if (draft != saved) onCommit(draft)
}
