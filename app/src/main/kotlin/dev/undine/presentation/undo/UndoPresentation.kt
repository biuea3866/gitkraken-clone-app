package dev.undine.presentation.undo

import dev.undine.application.undo.UndoTarget
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

/** 실행 결과를 화면 안내 문구로 옮긴다. 거부 결과는 절대 성공 문구로 바꾸지 않는다. */
fun undoOutcomeMessage(outcome: UndoOutcome?, strings: Strings): String? = when (outcome) {
    null -> null
    is UndoOutcome.Undone -> strings.undo.undone(outcome.operation.label)
    is UndoOutcome.Refused -> outcome.toUndoDisabledReason(strings)
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
