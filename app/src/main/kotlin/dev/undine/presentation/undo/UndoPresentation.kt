package dev.undine.presentation.undo

import dev.undine.application.undo.UndoExecution
import dev.undine.application.undo.UndoTarget
import dev.undine.domain.UndineException
import dev.undine.domain.undo.OperationEntry
import dev.undine.domain.undo.UndoOutcome
import dev.undine.presentation.i18n.Strings
import dev.undine.presentation.i18n.undo

/** Undo 버튼이 그릴 레이블·툴팁·비활성화 사유. */
data class UndoButtonPresentation(
    val label: String,
    val tooltip: String?,
    val disabledReason: String?,
    val enabled: Boolean,
)

/** 실행 이력의 한 행. [entry]가 시각·대상 등 원본 표시값을 보존한다. */
data class UndoHistoryRow(
    val entry: OperationEntry,
    val availability: String,
    val irreversibleReason: String?,
)

/**
 * 다음 Undo 대상의 상태를 화면 문구로 바꾼다.
 *
 * domain 의 [UndoOutcome.Refused.reason]은 진단용 문장이라 여기서 직접 쓰지 않는다. 거부 타입만
 * 해석해 현재 로케일의 [Strings.undo] 리소스를 선택한다.
 */
fun undoButtonPresentation(
    target: UndoTarget,
    isUndoing: Boolean,
    strings: Strings,
): UndoButtonPresentation {
    if (isUndoing) {
        return UndoButtonPresentation(
            label = strings.undo.running,
            tooltip = null,
            disabledReason = null,
            enabled = false,
        )
    }

    return when (target) {
        UndoTarget.None -> UndoButtonPresentation(
            label = strings.undo.idleLabel,
            tooltip = null,
            disabledReason = strings.undo.nothingToUndo,
            enabled = false,
        )

        is UndoTarget.Undoable -> target.entry.toUndoButtonPresentation(strings, enabled = true, disabledReason = null)

        is UndoTarget.Blocked -> target.entry.toUndoButtonPresentation(
            strings = strings,
            enabled = false,
            disabledReason = target.refusal.toUndoDisabledReason(strings),
        )
    }
}

/** 스택이 돌려준 최신 우선 순서를 그대로 유지해 패널 행을 만든다. */
fun undoHistoryPresentation(entries: List<OperationEntry>, strings: Strings): List<UndoHistoryRow> =
    entries.map { entry ->
        UndoHistoryRow(
            entry = entry,
            availability = if (entry.irreversibleReason == null) {
                strings.undo.historyReversible
            } else {
                strings.undo.historyIrreversible
            },
            irreversibleReason = entry.irreversibleReason,
        )
    }

/**
 * 실행 결말을 화면 안내 문구로 옮긴다.
 *
 * 거부·대상 어긋남·실패 중 어느 것도 성공 문구로 바뀌지 않는다. 특히 [UndoExecution.Failed] 는
 * **일부만 적용된 뒤 실패**했을 수 있어, 무엇이 실패했고 왜인지를 함께 말한다 (예외 처리 규칙 6).
 * `UndineException` 의 메시지는 infrastructure 가 이미 번역·마스킹한 것이라 그대로 실을 수 있다.
 */
fun undoExecutionMessage(execution: UndoExecution?, strings: Strings): String? = when (execution) {
    null -> null
    is UndoExecution.Completed -> execution.outcome.toUndoMessage(strings)
    is UndoExecution.Discarded -> strings.undo.discarded(execution.entry.operation.label)
    UndoExecution.TargetChanged -> strings.undo.targetChanged
    is UndoExecution.Failed -> strings.undo.failed(
        execution.entry.operation.label,
        execution.cause.message.orEmpty(),
    )
}

/**
 * 대상·이력을 읽지 못했을 때의 안내.
 *
 * 읽기 실패는 실행 결말과 다른 축이다 — 실행하지도 못한 상태이므로 결과 문구 자리에 섞지 않는다.
 */
fun undoLoadFailureMessage(failure: UndineException?, strings: Strings): String? =
    failure?.let { strings.undo.loadFailed(it.message.orEmpty()) }

private fun UndoOutcome.toUndoMessage(strings: Strings): String = when (this) {
    is UndoOutcome.Undone -> strings.undo.undone(operation.label)
    is UndoOutcome.Refused -> toUndoDisabledReason(strings)
}

private fun OperationEntry.toUndoButtonPresentation(
    strings: Strings,
    enabled: Boolean,
    disabledReason: String?,
): UndoButtonPresentation = UndoButtonPresentation(
    label = strings.undo.undoLabel(operation.label),
    tooltip = strings.undo.undoTooltip(operation.label, targetLabel),
    disabledReason = disabledReason,
    enabled = enabled,
)

private fun UndoOutcome.Refused.toUndoDisabledReason(strings: Strings): String = when (this) {
    UndoOutcome.NothingToUndo -> strings.undo.nothingToUndo
    is UndoOutcome.Irreversible -> strings.undo.irreversible(operation.label)
    is UndoOutcome.ExternalChange -> strings.undo.externalChange
    is UndoOutcome.NoCurrentBranch -> strings.undo.detachedHead
    is UndoOutcome.UncommittedChanges -> strings.undo.uncommittedChanges
    is UndoOutcome.UnmergedBranch -> strings.undo.unmergedBranch
}
