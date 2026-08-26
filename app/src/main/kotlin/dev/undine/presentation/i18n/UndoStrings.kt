package dev.undine.presentation.i18n

import java.util.Locale

/**
 * `undo.*` 네임스페이스 — 되돌리기 버튼과 실행 이력 패널의 문구.
 *
 * UND-63 이 [builtInTranslations] 등록까지 해 두었고, 키 정의 object·접근자 value class·로케일별
 * 번역은 UND-43(Undo 버튼 · 실행 이력 패널)이 **이 파일 안에서만** 채운다. 공통 파일
 * (`BuiltInStrings.kt`)은 건드리지 않는다 — 같은 wave 의 화면 7건이 그 파일을 함께 고치면 충돌한다.
 *
 * 연산 종류의 사용자 노출 이름은 `GitOperationKind.label` 이 이미 갖고 있다 — 그 이름을 여기에
 * 다시 적지 않고 인자로 받는다.
 *
 * **domain 의 거부 사유 문장을 그대로 노출하지 않는다.** `UndoOutcome.Refused.reason` 은 진단용
 * 한국어 하드코딩이라 로케일을 따르지 않는다 — 화면 문구는 여기의 키로만 만든다.
 *
 * `MessageFormat` 패턴이라 작은따옴표를 쓰지 않는다 — 인용부호가 인자 치환을 통째로 막는다.
 */
internal const val UNDO_NAMESPACE: String = "undo"

/** `undo.*` 키 정의 — 버튼 레이블·툴팁, 불가 사유, 이력 패널. */
object UndoKeys {
    val idleLabel = StringKey("$UNDO_NAMESPACE.button.idle")
    val undoLabel = StringKey("$UNDO_NAMESPACE.button.undo")
    val undoTooltip = StringKey("$UNDO_NAMESPACE.button.tooltip")
    val discardLabel = StringKey("$UNDO_NAMESPACE.button.discard")
    val running = StringKey("$UNDO_NAMESPACE.running")
    val undone = StringKey("$UNDO_NAMESPACE.result.undone")
    val discarded = StringKey("$UNDO_NAMESPACE.result.discarded")
    val failed = StringKey("$UNDO_NAMESPACE.result.failed")
    val loadFailed = StringKey("$UNDO_NAMESPACE.result.loadFailed")
    val targetChanged = StringKey("$UNDO_NAMESPACE.reason.targetChanged")
    val nothingToUndo = StringKey("$UNDO_NAMESPACE.reason.nothingToUndo")
    val irreversible = StringKey("$UNDO_NAMESPACE.reason.irreversible")
    val externalChange = StringKey("$UNDO_NAMESPACE.reason.externalChange")
    val detachedHead = StringKey("$UNDO_NAMESPACE.reason.detachedHead")
    val uncommittedChanges = StringKey("$UNDO_NAMESPACE.reason.uncommittedChanges")
    val unmergedBranch = StringKey("$UNDO_NAMESPACE.reason.unmergedBranch")
    val historyTitle = StringKey("$UNDO_NAMESPACE.history.title")
    val historyEmpty = StringKey("$UNDO_NAMESPACE.history.empty")
    val historyReversible = StringKey("$UNDO_NAMESPACE.history.reversible")
    val historyIrreversible = StringKey("$UNDO_NAMESPACE.history.irreversible")

    /** 번역 누락 검사가 키를 하나씩 나열하지 않도록 전체 목록을 노출한다. */
    val all: List<StringKey> = listOf(
        idleLabel, undoLabel, undoTooltip, discardLabel, running, undone, discarded, failed, loadFailed,
        targetChanged, nothingToUndo, irreversible, externalChange, detachedHead, uncommittedChanges,
        unmergedBranch,
        historyTitle, historyEmpty, historyReversible, historyIrreversible,
    )
}

/**
 * Undo 문구 접근자. `strings.undo.undoLabel(operation)` 로 읽는다.
 *
 * 버튼 레이블·툴팁이 **대상 동작 이름을 인자로 받는** 이유는 "실행 취소" 만 보고 누르면 무엇이
 * 취소될지 모르기 때문이다.
 */
@JvmInline
value class UndoStrings internal constructor(private val strings: Strings) {

    /** 되돌릴 것이 없을 때의 버튼 레이블. 대상이 없으니 동작 이름을 붙이지 않는다. */
    val idleLabel: String get() = strings.text(UndoKeys.idleLabel)

    /** 되돌릴 수 없는 최상단 기록을 이력에서 지우는 동작. 저장소가 아니라 세션 기록만 지운다. */
    val discardLabel: String get() = strings.text(UndoKeys.discardLabel)

    val running: String get() = strings.text(UndoKeys.running)

    /** 보여준 대상과 실제 최상단이 어긋나 아무것도 하지 않았을 때. */
    val targetChanged: String get() = strings.text(UndoKeys.targetChanged)

    val nothingToUndo: String get() = strings.text(UndoKeys.nothingToUndo)
    val externalChange: String get() = strings.text(UndoKeys.externalChange)
    val detachedHead: String get() = strings.text(UndoKeys.detachedHead)
    val uncommittedChanges: String get() = strings.text(UndoKeys.uncommittedChanges)
    val unmergedBranch: String get() = strings.text(UndoKeys.unmergedBranch)
    val historyTitle: String get() = strings.text(UndoKeys.historyTitle)
    val historyEmpty: String get() = strings.text(UndoKeys.historyEmpty)
    val historyReversible: String get() = strings.text(UndoKeys.historyReversible)
    val historyIrreversible: String get() = strings.text(UndoKeys.historyIrreversible)

    /** `커밋 취소` — 무엇이 취소되는지를 레이블 자체가 말한다. */
    fun undoLabel(operation: String): String = strings.text(UndoKeys.undoLabel, operation)

    /** 레이블보다 한 줄 더 — 되돌릴 대상까지 말한다. */
    fun undoTooltip(operation: String, target: String): String =
        strings.text(UndoKeys.undoTooltip, operation, target)

    fun undone(operation: String): String = strings.text(UndoKeys.undone, operation)

    /** 되돌리지 않고 기록만 지웠다 — 되돌렸다는 문구와 절대 섞이지 않게 따로 둔다. */
    fun discarded(operation: String): String = strings.text(UndoKeys.discarded, operation)

    /**
     * 되돌리다 실패했다. [detail]은 이미 번역·마스킹된 도메인 예외 메시지다 —
     * JGit 원문이나 원격 URL 이 여기로 오지 않는다 (예외 처리 규칙 2).
     */
    fun failed(operation: String, detail: String): String =
        strings.text(UndoKeys.failed, operation, detail)

    /** 대상·이력을 읽지 못했을 때. 버튼이 왜 잠겼는지를 빈 화면으로 두지 않는다. */
    fun loadFailed(detail: String): String = strings.text(UndoKeys.loadFailed, detail)

    fun irreversible(operation: String): String = strings.text(UndoKeys.irreversible, operation)
}

/** Undo 문구 네임스페이스 진입점. */
val Strings.undo: UndoStrings get() = UndoStrings(this)

internal val undoTranslations: Map<Locale, Map<StringKey, String>> = mapOf(
    Locale.KOREAN to mapOf(
        UndoKeys.idleLabel to "실행 취소",
        UndoKeys.undoLabel to "{0} 취소",
        UndoKeys.undoTooltip to "{0} 취소 — 대상: {1}",
        UndoKeys.discardLabel to "이 기록 지우기",
        UndoKeys.running to "되돌리는 중…",
        UndoKeys.undone to "{0} 을(를) 되돌렸습니다.",
        UndoKeys.discarded to "되돌릴 수 없는 {0} 기록을 이력에서 지웠습니다.",
        UndoKeys.failed to "{0} 을(를) 되돌리지 못했습니다: {1}",
        UndoKeys.loadFailed to "되돌리기 상태를 읽지 못했습니다: {0}",
        UndoKeys.targetChanged to "되돌릴 대상이 바뀌어 아무것도 실행하지 않았습니다. 다시 확인하세요.",
        UndoKeys.nothingToUndo to "되돌릴 작업이 없습니다",
        UndoKeys.irreversible to "{0} 는 되돌릴 수 없습니다",
        UndoKeys.externalChange to "저장소가 외부에서 변경되어 되돌릴 수 없습니다",
        UndoKeys.detachedHead to "브랜치를 체크아웃한 상태에서만 되돌릴 수 있습니다.",
        UndoKeys.uncommittedChanges to
            "커밋되지 않은 변경이 있어 되돌릴 수 없습니다. 먼저 커밋하거나 스태시하세요.",
        UndoKeys.unmergedBranch to "브랜치에 병합되지 않은 커밋이 있어 되돌리지 않았습니다.",
        UndoKeys.historyTitle to "실행 이력",
        UndoKeys.historyEmpty to "이 세션에서 실행한 연산이 없습니다.",
        UndoKeys.historyReversible to "되돌릴 수 있음",
        UndoKeys.historyIrreversible to "되돌릴 수 없음",
    ),
    Locale.ENGLISH to mapOf(
        UndoKeys.idleLabel to "Undo",
        UndoKeys.undoLabel to "Undo {0}",
        UndoKeys.undoTooltip to "Undo {0} — target: {1}",
        UndoKeys.discardLabel to "Remove this entry",
        UndoKeys.running to "Undoing…",
        UndoKeys.undone to "Undid {0}.",
        UndoKeys.discarded to "Removed the {0} entry, which could not be undone, from the history.",
        UndoKeys.failed to "Could not undo {0}: {1}",
        UndoKeys.loadFailed to "Could not read the undo state: {0}",
        UndoKeys.targetChanged to "The undo target changed, so nothing was run. Please check again.",
        UndoKeys.nothingToUndo to "There is nothing to undo.",
        UndoKeys.irreversible to "{0} cannot be undone.",
        UndoKeys.externalChange to "The repository changed outside the app, so this cannot be undone.",
        UndoKeys.detachedHead to "You can only undo while a branch is checked out.",
        UndoKeys.uncommittedChanges to
            "You have uncommitted changes, so this cannot be undone. Commit or stash them first.",
        UndoKeys.unmergedBranch to "The branch has unmerged commits, so it was not deleted.",
        UndoKeys.historyTitle to "Operation history",
        UndoKeys.historyEmpty to "No operations were run in this session.",
        UndoKeys.historyReversible to "Undoable",
        UndoKeys.historyIrreversible to "Not undoable",
    ),
)
